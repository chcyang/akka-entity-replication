package lerna.akka.entityreplication.raft

import akka.actor.{ ActorPath, ActorRef }
import lerna.akka.entityreplication.ClusterReplicationSerializable
import lerna.akka.entityreplication.model.{ EntityInstanceId, NormalizedEntityId }
import lerna.akka.entityreplication.raft.model.{ LogEntry, LogEntryIndex }
import lerna.akka.entityreplication.raft.snapshot.SnapshotProtocol.{
  EntitySnapshot,
  EntitySnapshotMetadata,
  EntityState,
}

private[entityreplication] object RaftProtocol {

  sealed trait RaftActorCommand
  sealed trait EntityCommand

  final case class RequestRecovery(entityId: NormalizedEntityId)                          extends RaftActorCommand
  final case class RecoveryState(events: Seq[LogEntry], snapshot: Option[EntitySnapshot]) extends EntityCommand

  final case class Command(command: Any)              extends ClusterReplicationSerializable with RaftActorCommand with EntityCommand
  final case class ForwardedCommand(command: Command) extends ClusterReplicationSerializable with RaftActorCommand
  final case class Replica(logEntry: LogEntry)        extends EntityCommand

  object Replicate {
    def apply(
        event: Any,
        replyTo: ActorRef,
        entityId: NormalizedEntityId,
        instanceId: EntityInstanceId,
        originSender: ActorRef,
    ): Replicate = {
      Replicate(event, replyTo, Option(entityId), Option(instanceId), Option(originSender))
    }

    def internal(event: Any, replyTo: ActorRef): Replicate = {
      Replicate(event, replyTo, None, None, None)
    }
  }

  final case class Replicate(
      event: Any,
      replyTo: ActorRef,
      entityId: Option[NormalizedEntityId],
      instanceId: Option[EntityInstanceId],
      originSender: Option[ActorRef],
  ) extends RaftActorCommand

  sealed trait ReplicationResponse

  final case class ReplicationSucceeded(event: Any, logEntryIndex: LogEntryIndex, instanceId: Option[EntityInstanceId])
      extends ReplicationResponse
      with EntityCommand

  final case class TakeSnapshot(metadata: EntitySnapshotMetadata, replyTo: ActorRef) extends EntityCommand
  final case class Snapshot(metadata: EntitySnapshotMetadata, state: EntityState)    extends RaftActorCommand

  final case object RecoveryTimeout extends EntityCommand

  final case class EntityRecoveryTimeoutException(entityPath: ActorPath) extends RuntimeException
}
