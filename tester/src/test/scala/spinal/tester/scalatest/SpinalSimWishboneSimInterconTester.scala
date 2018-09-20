package spinal.tester.scalatest

import org.scalatest.FunSuite
import spinal.tester
import spinal.core._
import spinal.core.sim._
import spinal.sim._
import spinal.lib._
import spinal.lib.bus.misc._
import spinal.lib.bus.wishbone._
import spinal.lib.wishbone.sim._
import spinal.lib.sim._
import scala.util.Random

class WishboneInterconComponent(config : WishboneConfig,n_masters: Int,decodings : Seq[SizeMapping]) extends Component{
  val io = new Bundle{
    val busMasters = Vec(slave(Wishbone(config)),n_masters)
    val busSlaves = Vec(master(Wishbone(config)),decodings.size)
  }
  val intercon = new WishboneInterconFactory(config)
  val slaves = io.busSlaves zip decodings
  slaves.foreach(println(_))
  intercon.addMasters(io.busMasters)
  intercon.addSlaves(slaves)
  intercon.build()
}

class SpinalSimWishboneSimInterconTester extends FunSuite{
  def testIntercon(config : WishboneConfig,decodings : Seq[SizeMapping],masters: Int,description : String = ""): Unit = {
    val fixture = SimConfig.allOptimisation.withWave.compile(rtl = new WishboneInterconComponent(config,masters,decodings))
    fixture.doSim(description){ dut =>

      // def send_transaction(id: BigInt,master: Wishbone,slave: (Wishbone,SizeMapping),repeat: Int = 10): Unit@suspendable = {
      //   val scoreboard_master = ScoreboardInOrder[WishboneTransaction]()
      //   val sequencer_master = WishboneSequencer{
      //     WishboneTransaction(data=id).randomAdressInRange(slave._2)
      //   }
      //   val driver_master = new WishboneDriver(master,dut.clockDomain)
      //   val monitor_master = WishboneMonitor(master,dut.clockDomain){ bus =>
      //     if(AddressRange.SizeMapping2AddressRange(slave._2).inRange(bus.ADR.toBigInt)) scoreboard_master.pushRef(WishboneTransaction.sampleAsSlave(bus))
      //     else println("noDice")
      //   }
      //   val driver_slave = new WishboneDriver(slave._1,dut.clockDomain)
      //   val monitor_slave = WishboneMonitor(slave._1,dut.clockDomain){ bus =>
      //     val ID = id
      //     val transaction = WishboneTransaction.sampleAsSlave(bus)
      //     transaction match{
      //       case WishboneTransaction(_,ID,_,_,_) => scoreboard_master.pushDut(transaction)
      //       case _ =>
      //     }
      //   }
      //   sequencer_master.generateTransactions()
      //   driver_slave.slaveSink()
      //   driver_master.drive(sequencer_master.nextTransaction,true)
      //   waitUntil(scoreboard_master.matches >= 1)
      // }

      def send_transaction(id: BigInt,master: Wishbone,slaves: Seq[(Wishbone,SizeMapping)],req: Int = 1): Unit@suspendable = {
        val scoreboard_master = ScoreboardInOrder[WishboneTransaction]()
        val sequencer_master = WishboneSequencer{
          WishboneTransaction(data=id)
        }
        val driver_master = new WishboneDriver(master,dut.clockDomain)
        def driver_slave(slave: (Wishbone,SizeMapping)) = new WishboneDriver(slave._1,dut.clockDomain)

        val monitor_master = WishboneMonitor(master,dut.clockDomain){ bus =>
          scoreboard_master.pushRef(WishboneTransaction.sampleAsSlave(bus))
        }

        def monitor_slave(slave: (Wishbone,SizeMapping),scoreboard: ScoreboardInOrder[WishboneTransaction]) = WishboneMonitor(slave._1,dut.clockDomain){ bus =>
          val ID = id
          val transaction = WishboneTransaction.sampleAsSlave(bus)
          transaction match{
            case WishboneTransaction(_,ID,_,_,_) => scoreboard.pushDut(transaction)
            case _ =>
          }
        }

        slaves.suspendable.foreach{slave =>
          driver_slave(slave).slaveSink()
          monitor_slave(slave, scoreboard_master)
          (0 to req).foreach{x => sequencer_master.addTransaction(WishboneTransaction(data=id).randomAdressInRange(slave._2))}
        }
        (1 to sequencer_master.transactions.size).suspendable.foreach{ x =>
            driver_master.drive(sequencer_master.nextTransaction,true)
            dut.clockDomain.waitSampling()
        }
        waitUntil(scoreboard_master.matches >= req*sequencer_master.transactions.size)
      }

      dut.clockDomain.forkStimulus(period=10)

      SimTimeout(1000*10000)

      dut.io.busMasters.suspendable.foreach{ bus =>
        bus.CYC #= false
        bus.STB #= false
        bus.WE #= false
        bus.ADR #= 0
        bus.DAT_MOSI #= 0
        if(bus.config.useLOCK) bus.LOCK #= false
      }

      dut.io.busSlaves.suspendable.foreach{ bus =>
        bus.ACK #= false
        bus.DAT_MISO #= 0
      }
      dut.clockDomain.waitSampling(10)
      val masters = dut.io.busMasters
      val slaves = dut.slaves
      val n_transactions = 10
      val masterPool = scala.collection.mutable.ListBuffer[SimThread]()
      val sss = scala.collection.mutable.ListBuffer[((Wishbone,Int),(Wishbone,SizeMapping))]()
      // for (master <- masters.zipWithIndex; slave <-slaves){
      //   masterPool += fork{
      //     println("%s => %s".format(master,slave))
      //     send_transaction(master._2,master._1,slave,n_transactions)
      //     println("DONE! %s => %s".format(master,slave))
      //   }
      // }

      // masterPool.suspendable.foreach{process => process.join()}

      masters.zipWithIndex.suspendable.foreach{master =>
        masterPool += fork{
          send_transaction(master._2,master._1,slaves,10)
        }
      }

      masterPool.suspendable.foreach{process => process.join()}
      dut.clockDomain.waitSampling(10)
    }
  }

  test("classicWishboneIntercon"){
    val masters = 10
    val slaves = 2
    val size = 1024
    val config = WishboneConfig(32,16)
    val decodings = for(i <- 1 to slaves) yield SizeMapping(i*size,size-1)
    decodings.foreach(println(_))
    testIntercon(config,decodings,masters,"classicWishboneIntercon")
  }

  test("pipelinedWishboneDecoder"){
    val masters = 10
    val slaves = 2
    val size = 1024
    val config = WishboneConfig(32,16).pipelined
    val decodings = for(i <- 1 to slaves) yield SizeMapping(i*size,size-1)
    decodings.foreach(println(_))
    testIntercon(config,decodings,masters,"pipelinedWishboneDecoder")
  }

}