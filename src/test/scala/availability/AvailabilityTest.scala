package availability

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

import com.typesafe.config.ConfigFactory

import Recovery.EmbeddedDown
import Recovery.NullEmbeddedState
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.cluster.Member
import akka.testkit.ImplicitSender
import akka.testkit.TestKit

class RecoveryTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  val config = ConfigFactory.parseString(s"""
            akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
            akka.remote {
              netty.tcp {
                hostname = "localhost"
                port = 1234 
              }
            }
            
            akka.cluster {
              seed-nodes = [
                "akka.tcp://ClusterSystem@localhost:2551"
               ]
               roles=["two"]
              auto-down-unreachable-after = 3s
            }
            """)

  def this() = this(ActorSystem("MySpec"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "An Recovery actor" must {

    class Simple extends Actor {
      def receive = {
        case EmbeddedDown => sender ! NullEmbeddedState
        case m @ _ => println(m)
      }
    }

    "start" in {
      val manager = system.actorOf(Recovery.props(() => ActorSystem("name",config),
        _.actorOf(Props(new Simple())),
        (_: Set[Member], m: Member) => m.hasRole("two")))
    }

  }

}