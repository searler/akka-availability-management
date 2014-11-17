package availability

import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import org.scalatest.{ BeforeAndAfterAll, WordSpecLike }
import org.scalatest.Matchers
import akka.actor._
import com.typesafe.config.ConfigFactory
import Recovery.EmbeddedDown
import Recovery.NullEmbeddedState
import akka.cluster.Member
import akka.cluster.ClusterEvent._
import Recovery._
import Redirector._
import scala.concurrent.duration._
import akka.remote.transport.ThrottlerTransportAdapter.Direction


object RecoveryMultiNodeConfig extends MultiNodeConfig {
  val controller = role("controller")
  val server1 = role("server1") // 1
  val server2 = role("server2") // 2
  val client1 = role("client1") // 3

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.log-dead-letters = off
    akka.log-dead-letters-during-shutdown = off
    akka.remote.log-remote-lifecycle-events = ERROR
    akka.testconductor.barrier-timeout = 60 s
    """))

  nodeConfig(server1)(
    ConfigFactory.parseString("akka.cluster.roles =[svr,svr1]"))

  nodeConfig(server2)(
    ConfigFactory.parseString("akka.cluster.roles =[svr,svr2]"))

  nodeConfig(client1)(
    ConfigFactory.parseString("akka.cluster.roles =[ws,ws1]"))
}

class MultiNodeRecoverySpecMultiJvmNode1 extends MultiRecoveryTest
class MultiNodeRecoverySpecMultiJvmNode2 extends MultiRecoveryTest
class MultiNodeRecoverySpecMultiJvmNode3 extends MultiRecoveryTest
class MultiNodeRecoverySpecMultiJvmNode4 extends MultiRecoveryTest

class MultiRecoveryTest extends MultiNodeSpec(RecoveryMultiNodeConfig)
  with WordSpecLike with Matchers with BeforeAndAfterAll
  with ImplicitSender {
  import RecoveryMultiNodeConfig._

  def initialParticipants = roles.size
  
  var m:ActorRef = _

  def start(roles: String, portIndex: Int) = {
    val seedAddress = node(server1).address.toString
    val config = ConfigFactory.parseString(s"""
          akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
            akka.remote {
              netty.tcp {
                hostname = "golem"
                port = "${1234 + portIndex}" 
              }
            }
            
            akka.cluster {
              seed-nodes = [
                "akka.tcp://ClusterSystem@golem:1234"
               ]
               roles=["$roles"]
              auto-down-unreachable-after = 1s
            }
            """)
    m = system.actorOf(Recovery.props(() => ActorSystem("ClusterSystem", config),
      _.actorOf(Redirector.props()),
      (_: Set[Member], m: Member) => m.hasRole("srv")))
  }

  "Recovery " must {

    "wait for all nodes to enter a barrier" in {
      enterBarrier("startup")
    }
    
    "deploy" in {
      runOn(controller) {
        enterBarrier("deployed")
      }
      runOn(server1) {
         start("srv1", 0)
        m ! Register(self)
        enterBarrier("deployed")
      }

      runOn(server2) {
         start("srv2", 1)
        m ! Register(self)
        enterBarrier("deployed")
      }

      runOn(client1) {
        start("ws1", 2)
        m ! Register(self)
        m ! RegisterClusterListener(self)
        List("srv1", "srv2").foreach {
          role => expectMsgPF(4 seconds) { case MemberUp(p) if p.hasRole(`role`) => () }
        }
         enterBarrier("deployed")
      }
    }

    "redirect" in {
      runOn(server1) {
        expectMsg(4 seconds, "hello")
      }

      runOn(server2) {
        expectMsg(4 seconds, "there")
      }

      runOn(client1) {
        m ! Send("srv1", "testActor1", "hello")
        m ! Send("srv2", "testActor1", "there")
      }

      enterBarrier("redirected")
    }
    
   
    
    
  }

  override def beforeAll() = multiNodeSpecBeforeAll()
  override def afterAll() = multiNodeSpecAfterAll()
}