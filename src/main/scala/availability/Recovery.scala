package availability

import scala.concurrent.duration.DurationLong
import com.typesafe.config.Config
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.FSM
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.Member
import java.util.concurrent.TimeUnit
import scala.util.Try
import com.typesafe.config.ConfigException

/**
 * Manage sacrificial cluster and embedded actor instance
 * 
 * Recreate cluster when connectivity to all or specific nodes is lost.
 * 
 * The 'recovery.timeout' configuration parameter specifies how long to wait
 * for the embedded actor to respond with its current state. Defaults to 1 second.
 *
 */
object Recovery {

  /**
   * Registers cluster state listener via the RecoveryActor
   *
   * @param listener receives MemberEvent from the sacrificial ActorSystem
   */
  case class RegisterClusterListener(listener: ActorRef)

  /**
   * Sent to embedded actor to request that it reply with
   * its current state and terminate
   */
  case object EmbeddedDown

  /**
   * Trait from which all state of the embedded actor that must survive restart
   * derives
   */
  trait EmbeddedState
  /**
   * Embedded actor has no state that needs to survive restart
   */
  case object NullEmbeddedState extends EmbeddedState

  /**
   * @param systemBuilder create the sacrificial ActorSystem
   * @param embeddedBuilder create the Actor contained within the ActorSystem
   * @param restart (previously connected nodes,downed node) determine if ActorSystem must be restarted
   */
  def props(systemBuilder: () => ActorSystem,
    embeddedBuilder: (ActorSystem) => ActorRef,
    restart: (Set[Member], Member) => Boolean) =
    Props(classOf[RecoveryActor], systemBuilder, embeddedBuilder, restart)

  //---------------- internal ----------------------  

  private sealed trait State
  private case object Running extends State
  private case object Stopping extends State
  private case object AwaitShutdown extends State

  private sealed trait Data
  private case class RestartState(state: EmbeddedState) extends Data
  private case class RunState(system: ActorSystem, cluster: Cluster, embedded: ActorRef, members: Set[Member]) extends Data

  private class RecoveryActor(systemBuilder: () => ActorSystem,
    embeddedBuilder: (ActorSystem) => ActorRef,
    restart: (Set[Member], Member) => Boolean)
    extends FSM[State, Data] {

    startWith(Running, start(RestartState(NullEmbeddedState)))

    when(Running) {
      case Event(RegisterClusterListener(listener), state: RunState) =>
        state.cluster.subscribe(listener, initialStateMode = InitialStateAsEvents,
          classOf[MemberEvent])
        stay
      case Event(MemberRemoved(member, _), state: RunState) =>
        (state.members - member) match {
          case members if members.size == 1 |
            restart(state.members, member) =>
            state.embedded ! EmbeddedDown
            goto(Stopping) forMax (timeout(state))
          case members => stay using (state.copy(members = members))
        }

      case Event(MemberUp(member), state: RunState) =>
        stay using (state.copy(members = state.members + member))
      case Event(_: MemberEvent, _) => stay
      case Event(msg, state: RunState) =>
        state.embedded ! msg
        stay
    }

    when(Stopping) {
      case Event(StateTimeout, state: RunState) =>
        state.system.shutdown
        goto(AwaitShutdown) using RestartState(NullEmbeddedState)
      case Event(response: EmbeddedState, state: RunState) =>
        state.system.shutdown
        goto(AwaitShutdown) using RestartState(response)
    }

    when(AwaitShutdown) {
      case Event(SystemDown, state: RestartState) =>
        goto(Running) using start(state)
    }

    onTermination {
      case StopEvent(_, _, RunState(system, _, _, _)) => system.shutdown
    }

    initialize

    private def start(state: RestartState): RunState = {
      val system = systemBuilder()
      system.registerOnTermination(self ! SystemDown)
      val cluster = Cluster(system)
      cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
        classOf[MemberEvent])
      val embedded = embeddedBuilder(system)
      embedded ! state.state
      RunState(system, cluster, embedded, Set())
    }

    private def timeout(state: RunState) = Try(
      state.system.settings.config.getDuration("recovery.timeout", TimeUnit.MILLISECONDS) millis).
      recover { case _: ConfigException.Missing => 1 second }.get

    private case object SystemDown
  }

}