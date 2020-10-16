package lerna.akka.entityreplication.raft

import akka.actor.{ ActorPath, ActorRef }
import lerna.akka.entityreplication.model.NormalizedEntityId
import lerna.akka.entityreplication.raft.RaftProtocol._
import lerna.akka.entityreplication.raft.model._
import lerna.akka.entityreplication.raft.protocol.RaftCommands._
import lerna.akka.entityreplication.raft.protocol.{ SuspendEntity, TryCreateEntity }
import lerna.akka.entityreplication.raft.snapshot.SnapshotProtocol
import lerna.akka.entityreplication.{ ReplicationActor, ReplicationRegion }

trait Leader { this: RaftActor =>
  import RaftActor._

  def leaderBehavior: Receive = {

    case HeartbeatTimeout =>
      publishAppendEntries()

    case request: RequestVote                                 => receiveRequestVote(request)
    case response: RequestVoteResponse                        => ignoreRequestVoteResponse(response)
    case request: AppendEntries                               => receiveAppendEntries(request)
    case response: AppendEntriesResponse                      => receiveAppendEntriesResponse(response)
    case request: Command                                     => handleCommand(request)
    case ForwardedCommand(request)                            => handleCommand(request)
    case Replicate(event, replyTo, entityId)                  => replicate(event, replyTo, entityId)
    case response: ReplicationResponse                        => receiveReplicationResponse(response)
    case ReplicationRegion.Passivate(entityPath, stopMessage) => startEntityPassivationProcess(entityPath, stopMessage)
    case TryCreateEntity(_, entityId)                         => createEntityIfNotExists(entityId)
    case RequestRecovery(entityId)                            => recoveryEntity(entityId)
    case response: SnapshotProtocol.FetchSnapshotResponse     => receiveFetchSnapshotResponse(response)
    case SuspendEntity(_, entityId, stopMessage)              => suspendEntity(entityId, stopMessage)
    case SnapshotTick                                         => handleSnapshotTick()
    case response: ReplicationActor.Snapshot                  => receiveEntitySnapshotResponse(response)
    case response: SnapshotProtocol.SaveSnapshotResponse      => receiveSaveSnapshotResponse(response)
  }

  private[this] def receiveRequestVote(res: RequestVote): Unit =
    res match {

      case RequestVote(_, term, candidate, lastLogIndex, lastLogTerm)
          if term.isNewerThan(
            currentData.currentTerm,
          ) && lastLogTerm >= currentData.replicatedLog.lastLogTerm && lastLogIndex >= currentData.replicatedLog.lastLogIndex =>
        log.debug(s"=== [Leader] accept RequestVote($term, $candidate) ===")
        cancelHeartbeatTimeoutTimer()
        applyDomainEvent(Voted(term, candidate)) { domainEvent =>
          sender() ! RequestVoteAccepted(domainEvent.term, selfMemberIndex)
          become(Follower)
        }

      case request: RequestVote =>
        log.debug(s"=== [Leader] deny $request ===")
        sender() ! RequestVoteDenied(currentData.currentTerm)
    }

  private[this] def ignoreRequestVoteResponse(res: RequestVoteResponse): Unit =
    res match {

      case RequestVoteAccepted(term, _) if term == currentData.currentTerm => // ignore

      case RequestVoteDenied(term) if term == currentData.currentTerm => // ignore

      case other =>
        unhandled(other) // TODO: 不具合の可能性が高いのでエラーとして報告
    }

  private[this] def receiveAppendEntries(res: AppendEntries): Unit =
    res match {

      case appendEntries: AppendEntries if appendEntries.leader == self => // ignore

      case appendEntries: AppendEntries if appendEntries.term.isNewerThan(currentData.currentTerm) =>
        if (currentData.hasMatchLogEntry(appendEntries.prevLogIndex, appendEntries.prevLogTerm)) {
          cancelHeartbeatTimeoutTimer()
          log.debug(s"=== [Leader] append $appendEntries ===")
          applyDomainEvent(AppendedEntries(appendEntries.term, appendEntries.entries, appendEntries.prevLogIndex)) {
            domainEvent =>
              applyDomainEvent(FollowedLeaderCommit(appendEntries.leader, appendEntries.leaderCommit)) { _ =>
                sender() ! AppendEntriesSucceeded(
                  domainEvent.term,
                  currentData.replicatedLog.lastLogIndex,
                  selfMemberIndex,
                )
                become(Follower)
              }
          }
        } else { // prevLogIndex と prevLogTerm がマッチするエントリが無かった
          log.debug(s"=== [Leader] could not append $appendEntries ===")
          cancelHeartbeatTimeoutTimer()
          if (appendEntries.term == currentData.currentTerm) {
            applyDomainEvent(DetectedLeaderMember(appendEntries.leader)) { _ =>
              sender() ! AppendEntriesFailed(currentData.currentTerm, selfMemberIndex)
              become(Follower)
            }
          } else {
            applyDomainEvent(DetectedNewTerm(appendEntries.term)) { domainEvent =>
              applyDomainEvent(DetectedLeaderMember(appendEntries.leader)) { _ =>
                sender() ! AppendEntriesFailed(domainEvent.term, selfMemberIndex)
                become(Follower)
              }
            }
          }
        }

      case _: AppendEntries =>
        sender() ! AppendEntriesFailed(currentData.currentTerm, selfMemberIndex)
    }

  private[this] def receiveAppendEntriesResponse(res: AppendEntriesResponse): Unit =
    res match {

      case succeeded: AppendEntriesSucceeded if succeeded.term.isNewerThan(currentData.currentTerm) =>
        unhandled(succeeded) // TODO: 不具合の可能性が高いのでエラーとして報告

      case succeeded: AppendEntriesSucceeded =>
        val follower = succeeded.sender
        applyDomainEvent(SucceededAppendEntries(follower, succeeded.lastLogIndex)) { _ =>
          val newCommitIndex = currentData.findReplicatedLastLogIndex(numberOfMembers, succeeded.lastLogIndex)
          if (newCommitIndex > currentData.commitIndex) {
            applyDomainEvent(Committed(newCommitIndex)) { _ =>
              // do nothing
            }
          }
        }

      case failed: AppendEntriesFailed if failed.term == currentData.currentTerm =>
        applyDomainEvent(DeniedAppendEntries(failed.sender)) { _ =>
          // do nothing
        }

      case failed: AppendEntriesFailed if failed.term.isNewerThan(currentData.currentTerm) =>
        cancelHeartbeatTimeoutTimer()
        applyDomainEvent(DetectedNewTerm(failed.term)) { _ =>
          become(Follower)
        }
    }

  private[this] def handleCommand(req: Command): Unit =
    req match {

      case Command(message) =>
        val (entityId, cmd) = extractEntityId(message)
        broadcast(TryCreateEntity(shardId, entityId))
        replicationActor(entityId) forward Command(cmd)
    }

  private[this] def replicate(event: Any, client: ActorRef, entityId: Option[NormalizedEntityId]): Unit = {
    cancelHeartbeatTimeoutTimer()
    applyDomainEvent(AppendedEvent(EntityEvent(entityId, event))) { _ =>
      applyDomainEvent(StartedReplication(client, currentData.replicatedLog.lastLogIndex)) { _ =>
        publishAppendEntries()
      }
    }
  }

  private[this] def receiveReplicationResponse(event: Any): Unit =
    event match {

      case ReplicationSucceeded(NoOp, _) =>
      // ignore: no-op replication when become leader

      case ReplicationSucceeded(unknownEvent, _) =>
        log.warning("unknown event: {}", unknownEvent)

      case ReplicationFailed(cause) =>
        log.warning("replication failure", cause)
    }

  private[this] def startEntityPassivationProcess(entityPath: ActorPath, stopMessage: Any): Unit = {
    broadcast(SuspendEntity(shardId, NormalizedEntityId.of(entityPath), stopMessage))
  }

  private[this] def publishAppendEntries(): Unit = {
    resetHeartbeatTimeoutTimer()
    otherMemberIndexes.foreach { memberIndex =>
      val nextIndex    = currentData.nextIndexFor(memberIndex)
      val prevLogIndex = nextIndex.prev()
      val prevLogTerm  = currentData.replicatedLog.get(prevLogIndex).map(_.term).getOrElse(Term.initial())
      val entries      = currentData.replicatedLog.getAllFrom(nextIndex)
      val message = AppendEntries(
        shardId,
        currentData.currentTerm,
        selfMemberIndex,
        prevLogIndex,
        prevLogTerm,
        entries,
        currentData.commitIndex,
      )
      log.debug(s"=== [Leader] publish $message to $memberIndex ===")
      region ! ReplicationRegion.DeliverTo(memberIndex, message)
    }
  }
}
