package availability

import com.typesafe.config.ConfigFactory

import Recovery.EmbeddedDown
import Recovery.EmbeddedState
import Recovery.NullEmbeddedState
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.cluster.Member

object RecoveryDriver extends App {
  import Recovery._
  val system = ActorSystem("Driver")
  val port = args(0).toInt
  val role = args(1)

  val cluster = "ClusterSystem"

  val config = ConfigFactory.parseString(s"""
            recovery{
             timeout=12s
            }
            akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
            akka.remote {
              netty.tcp {
                hostname = "localhost"
                port = $port 
              }
            }
            
            akka.cluster {
              seed-nodes = [
                "akka.tcp://$cluster@localhost:2551"
               ]
               roles=["$role"]
              auto-down-unreachable-after = 3s
            }
            """)

  val manager = system.actorOf(Recovery.props(() => ActorSystem(cluster, config),
    _.actorOf(Redirector.props(), name = "embedded"),
    (_: Set[Member], m: Member) => m.hasRole("two")))

  val target = system.actorOf(Props[Simple], name = "target")
  manager ! Redirector.Register(target)

  while (true) {
    manager ! Redirector.Send("two", "target", Console.in.readLine())
  }

  class Simple extends Actor {
    def receive = {
      case EmbeddedDown =>
        sender ! State("response"); println("down")
      case State(previous) => println("init", previous)
      case NullEmbeddedState => println("init none")
      case m @ _ => println(m)
    }
    case class State(s: String) extends EmbeddedState
  }

}
       
    