package availability

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.Member
import availability.Recovery.{EmbeddedState, NullEmbeddedState, EmbeddedDown }
import akka.actor.ActorRef
import akka.actor.RootActorPath
import akka.actor.ActorLogging
import akka.actor.Props

object Redirector {

  case class Register(handler: ActorRef)
  case class Send(role: String, name: String, msg: Any)

  def props() = Props(classOf[RedirectorActor])

  private class RedirectorActor() extends Actor {

    override def preStart = {
      val cluster = Cluster(context.system)
      cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
        classOf[MemberEvent])
    }

    def receive = handle(Set(), Map())

    private def handle(members: Set[Member], registered: Map[String, ActorRef]): Receive = {
      case EmbeddedDown =>
        sender ! State(registered)
        context.stop(self)
      case State(registered) => context.become(handle(members, registered))
      case NullEmbeddedState => //noop
      case MemberUp(member) => context.become(handle(members + member, registered))
      case MemberRemoved(member, _) => context.become(handle(members - member, registered))
      case _: MemberEvent => //noop
      case Register(handler) => context.become(handle(members, registered + (handler.path.name -> handler)))
      case Send(role, handler, msg) => members.
        filter(_.hasRole(role)).
        foreach(m => context.actorSelection(RootActorPath(m.address) / "user" / context.self.path.name) ! Deliver(handler, msg))
      case Deliver(handler, msg) => registered.get(handler).foreach(_ ! msg);
    }

  }
  private case class Deliver(name: String, msg: Any)
  private case class State(registered: Map[String, ActorRef]) extends EmbeddedState
}