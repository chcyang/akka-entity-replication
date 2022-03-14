package lerna.akka.entityreplication.raft

import akka.Done
import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ typed, ActorSystem, Status }
import akka.persistence.inmemory.extension.{ InMemoryJournalStorage, InMemorySnapshotStorage, StorageExtension }
import akka.testkit.{ TestKit, TestProbe }
import com.typesafe.config.ConfigFactory
import lerna.akka.entityreplication.ClusterReplicationSettings
import lerna.akka.entityreplication.model.{ NormalizedEntityId, TypeName }
import lerna.akka.entityreplication.raft.RaftActor.{ CompactionCompleted, SnapshotTick }
import lerna.akka.entityreplication.raft.eventsourced.CommitLogStoreActor
import lerna.akka.entityreplication.raft.model._
import lerna.akka.entityreplication.raft.protocol.RaftCommands.{ AppendEntries, InstallSnapshot }
import lerna.akka.entityreplication.raft.snapshot.SnapshotProtocol._
import lerna.akka.entityreplication.util.{ RaftEventJournalTestKit, RaftSnapshotStoreTestKit }
import org.scalatest.BeforeAndAfterEach

class RaftActorSnapshotSynchronizationSpec
    extends TestKit(ActorSystem())
    with RaftActorSpecBase
    with BeforeAndAfterEach {

  private implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped

  private val settings                       = ClusterReplicationSettings.create(system)
  private val typeName                       = TypeName.from("test-type-1")
  private val shardId                        = createUniqueShardId()
  private val leaderMemberIndex              = createUniqueMemberIndex()
  private val leaderRaftSnapshotStoreTestKit = RaftSnapshotStoreTestKit(system, typeName, leaderMemberIndex, settings)
  private val raftEventJournalTestKit        = RaftEventJournalTestKit(system, settings)

  override def beforeEach(): Unit = {
    super.beforeEach()
    // clear storage
    val probe   = TestProbe()
    val storage = StorageExtension(system)
    probe.send(storage.journalStorage, InMemoryJournalStorage.ClearJournal)
    probe.send(storage.snapshotStorage, InMemorySnapshotStorage.ClearSnapshots)
    probe.receiveWhile(messages = 2) {
      case _: Status.Success => Done
    } should have length 2
    // reset SnapshotStore
    leaderRaftSnapshotStoreTestKit.reset()
    raftEventJournalTestKit.reset()
  }

  "RaftActor snapshot synchronization" should {

    val raftConfig = ConfigFactory
      .parseString("""
                     | lerna.akka.entityreplication.raft {
                     |   election-timeout = 99999s
                     |   # start compaction if the length of the log exceeds 2
                     |   compaction.log-size-threshold = 2
                     |   compaction.preserve-log-size = 1
                     | }
                     |""".stripMargin).withFallback(ConfigFactory.load())

    "prevent to start compaction during snapshot synchronization" in {
      /* prepare */
      val snapshotStore         = TestProbe()
      val replicationActorProbe = TestProbe()
      val commitLogStore        = TestProbe()
      val followerMemberIndex   = createUniqueMemberIndex()
      val follower = createRaftActor(
        typeName = typeName,
        shardId = shardId,
        selfMemberIndex = followerMemberIndex,
        shardSnapshotStore = snapshotStore.ref,
        replicationActor = replicationActorProbe.ref,
        settings = RaftSettings(raftConfig),
        commitLogStore = commitLogStore.ref,
      )
      val term                   = Term(1)
      val leaderSnapshotTerm     = term
      val leaderSnapshotLogIndex = LogEntryIndex(3)
      val entityId               = NormalizedEntityId("test-entity")
      val leaderSnapshots = Set(
        EntitySnapshot(EntitySnapshotMetadata(entityId, leaderSnapshotLogIndex), EntityState("state-1")),
      )
      val entityIds = leaderSnapshots.map(_.metadata.entityId)
      leaderRaftSnapshotStoreTestKit.saveSnapshots(leaderSnapshots)
      raftEventJournalTestKit.persistEvents(
        CompactionCompleted(leaderMemberIndex, shardId, leaderSnapshotTerm, leaderSnapshotLogIndex, entityIds),
      )

      /* check */
      follower ! AppendEntries(
        shardId,
        term,
        leaderMemberIndex,
        prevLogIndex = LogEntryIndex.initial(),
        prevLogTerm = Term.initial(),
        entries = Seq(
          LogEntry(LogEntryIndex(1), EntityEvent(None, NoOp), term),
          LogEntry(LogEntryIndex(2), EntityEvent(Option(entityId), "event-1"), term),
        ),
        leaderCommit = LogEntryIndex(2),
      )
      follower ! InstallSnapshot(
        shardId,
        term = term,
        srcMemberIndex = leaderMemberIndex,
        srcLatestSnapshotLastLogTerm = leaderSnapshotTerm,
        srcLatestSnapshotLastLogLogIndex = leaderSnapshotLogIndex,
      )

      // Let follower know that compaction can delete entries (indices <= 2)
      follower ! CommitLogStoreActor.AppendCommittedEntriesResponse(LogEntryIndex(2))

      LoggingTestKit.info("Skipping compaction because snapshot synchronization is in progress").expect {
        // trigger compaction
        follower ! SnapshotTick
      }
      LoggingTestKit.info("Snapshot synchronization completed").expect {
        snapshotStore.receiveWhile(messages = 1) {
          case msg: SaveSnapshot =>
            msg.replyTo ! SaveSnapshotSuccess(msg.snapshot.metadata)
        } should have length 1
        // compaction become available
      }

      follower ! AppendEntries(
        shardId,
        term,
        leaderMemberIndex,
        prevLogIndex = leaderSnapshotLogIndex,
        prevLogTerm = leaderSnapshotTerm,
        entries = Seq(
          LogEntry(LogEntryIndex(4), EntityEvent(Option(entityId), "event-4"), term),
          LogEntry(LogEntryIndex(5), EntityEvent(Option(entityId), "event-5"), term),
        ),
        leaderCommit = LogEntryIndex(5),
      )

      // Let follower know that compaction can delete entries (indices <= 5)
      follower ! CommitLogStoreActor.AppendCommittedEntriesResponse(LogEntryIndex(5))

      LoggingTestKit.info("compaction started").expect {
        // trigger compaction
        follower ! SnapshotTick
      }
    }
  }
}
