// See LICENSE for license details.

package rocketchip

import Chisel._
import cde.{Parameters, Field}
import junctions._
import uncore._
import rocket._
import zscale._

case object UseZscale extends Field[Boolean]
case object BuildZscale extends Field[(Bool, Parameters) => Zscale]
case object BootROMCapacity extends Field[Int]
case object DRAMCapacity extends Field[Int]

object ZscaleTopUtils {
  def printSystemInfo()(implicit p: Parameters) = {
    val addrHashMap = p(GlobalAddrHashMap)
    println("Generated Address Map")
    for ((name, base, region) <- addrHashMap.sortedEntries) {
      println(f"\t$name%s $base%x - ${base + region.size - 1}%x")
    }
    println("Generated Configuration String")
    println(new String(p(ConfigString)))
  }
  def makeBootROM()(implicit p: Parameters) = {
    val rom = java.nio.ByteBuffer.allocate(32)
    rom.order(java.nio.ByteOrder.LITTLE_ENDIAN)

    // for now, have the reset vector jump straight to memory
    val addrHashMap = p(GlobalAddrHashMap)
    val resetToMemDist = addrHashMap("mem").start
    require(resetToMemDist.toInt == (resetToMemDist.toInt >> 12 << 12))
    // TODO: zscale reset vector is 0x200
    val configStringAddr = Integer.parseInt("1000", 16) + rom.capacity

    rom.putInt(0x000002b7 + resetToMemDist.toInt) // lui t0, &mem
    rom.putInt(0x00028067)                        // jr t0
    rom.putInt(0)                                 // reserved
    rom.putInt(configStringAddr)                  // pointer to config string
    rom.putInt(0)                                 // default trap vector
    rom.putInt(0)                                 //   ...
    rom.putInt(0)                                 //   ...
    rom.putInt(0)                                 //   ...

    rom.array() ++ p(ConfigString).toSeq
  }
}

class HastiMasterIOData64to32Converter(implicit p: Parameters) extends HastiModule()(p) {
  val io = new Bundle {
    val in = new HastiMasterIO()(p.alterPartial({case hastiDataBits => 64})).flip
    val out = new HastiMasterIO()(p.alterPartial({case hastiDataBits => 64}))
  }
  val sz64 = Wire(init = Bool(false))

  io.out.haddr     := io.in.haddr    
  io.out.hwrite    := io.in.hwrite   
  io.out.hsize     := Mux(sz64, UInt(2), io.in.hsize)
  io.out.hburst    := io.in.hburst   
  io.out.hprot     := io.in.hprot    
  io.out.htrans    := io.in.htrans   
  io.out.hmastlock := io.in.hmastlock

  sz64 := (io.in.hsize === UInt(3))

  io.out.hwdata    := Mux(
    io.in.haddr(2) === UInt(1),
    io.in.hwdata(63,32),
    io.in.hwdata(31,0))

  io.in.hrdata := Cat(io.out.hrdata, io.out.hrdata)
  io.in.hready := io.out.hready
  io.in.hresp  := io.out.hresp
}

class ZscaleSystem(implicit p: Parameters)  extends Module {
  val io = new Bundle {
    val prci = new PRCITileIO().flip
    val host = new HostIO(p(HtifKey).width)
    val jtag = new HastiMasterIO().flip
    val bootmem = new HastiSlaveIO().flip
    val dram = new HastiSlaveIO().flip
    val spi = new HastiSlaveIO().flip
    val led = new PociIO
    val corereset = new PociIO
  }

  val core = p(BuildZscale)(io.prci.reset, p)

  val htif = Module(new HtifZ(CSRs.mreset))
  
  io.host <> htif.io.host
  
  val scrFile = Module(new SCRFile("ZSCALE_SCR", 0))
  scrFile.io.smi <> htif.io.scr

  val bootmem_afn = (addr: UInt) => addr(31, 14) === UInt(0)

  val sbus_afn = (addr: UInt) => addr(31, 29).orR
  val dram_afn = (addr: UInt) => addr(31, 26) === UInt(32) // 0x80000000
  val spi_afn = (addr: UInt) => addr(31, 26) === UInt(9) && addr(25, 14) === UInt(0)

  val pbus_afn = (addr: UInt) => addr(31) === UInt(1)
  val led_afn = (addr: UInt) => addr(31) === UInt(1) && addr(30, 10) === UInt(0)
  val corereset_afn = (addr: UInt) => addr(31) === UInt(1) && addr(30, 10) === UInt(1)

  val xbar = Module(new HastiXbar(3, Seq(bootmem_afn, sbus_afn)))
  val sadapter = Module(new HastiSlaveToMaster)
  val sbus = Module(new HastiBus(Seq(dram_afn, spi_afn, pbus_afn)))
  val padapter = Module(new HastiToPociBridge)
  val pbus = Module(new PociBus(Seq(led_afn, corereset_afn)))

  core.io.prci <> io.prci
  
  xbar.io.masters(0) <> htif.io.mem
  xbar.io.masters(1) <> core.io.dmem
  xbar.io.masters(2) <> core.io.imem

  io.bootmem <> xbar.io.slaves(0)
  sadapter.io.in <> xbar.io.slaves(1)

  sbus.io.master <> sadapter.io.out
  io.dram <> sbus.io.slaves(0)
  io.spi <> sbus.io.slaves(1)
  padapter.io.in <> sbus.io.slaves(2)

  pbus.io.master <> padapter.io.out
  io.led <> pbus.io.slaves(0)
  io.corereset <> pbus.io.slaves(1)

  ZscaleTopUtils.printSystemInfo
}

class ZscaleTop(topParams: Parameters) extends Module {
  implicit val p = topParams.alterPartial({case TLId => "L1toL2" })
  val io = new Bundle {
    val prci = new PRCITileIO().flip
    val host = new HostIO(p(HtifKey).width)
  }

  val sys = Module(new ZscaleSystem)
  val bootmem = Module(new HastiROM(ZscaleTopUtils.makeBootROM()))
  val dram = Module(new HastiSRAM(p(DRAMCapacity)/4))

  sys.io.prci <> io.prci
  sys.io.host <> io.host
  bootmem.io <> sys.io.bootmem
  dram.io <> sys.io.dram
}
