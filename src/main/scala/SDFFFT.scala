// SPDX-License-Identifier: Apache-2.0

package fft

import chisel3._
import chisel3.experimental._
import chisel3.util._

import dsptools._
import dsptools.numbers._

import breeze.numerics.{cos, sin}
import scala.math.Pi
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

/**
 * Base class for FFT parameters
 *
 * These are type generic
 */
 
sealed trait DecimType
case object DITDecimType extends DecimType
case object DIFDecimType extends DecimType
 
case class FFTParams[T <: Data] (
    numPoints       : Int ,                 // number of points in FFT
    protoTwiddle    : DspComplex[T],        // twiddle data type
    protoIQ         : DspComplex[T],        // input data type
    fftType         : String,               // type of FFT to use
    decimType       : DecimType,               // use DIT or DIF version
    sdfRadix        : String,               // radix
    protoIQstages   : Array[DspComplex[T]], // protoIQ on each stage
    expandLogic     : Array[Int],           // growing logic settings
    runTime         : Boolean,              // use run time configurable number of points (include fftSize register)
    keepMSBorLSB    : Array[Boolean],       // keep MSB - discards LSB (divide by 2), keep LSB discards MSB bit
    keepMSBorLSBReg : Boolean,              // use reg for keeping msb or lsb bit
    overflowReg     : Boolean,              // includes register for overflow indication
    runTimeR22      : Option[Boolean],      // only if radix 2^2 is used then this parameter has an effect on design
    trimType        : TrimType,             // TrimType - used for div2 and trimBinary
    numAddPipes     : Int,                  // number of pipeline registers after add/minus operation
    numMulPipes     : Int,                  // number of pipeline registers after multiplication operator
    fftDir          : Boolean,              // use fft or ifft
    fftDirReg       : Boolean,              // include register for configuring fft direction (fft or ifft)
    use4Muls        : Boolean,              // use 3 or 4 multiplier structure for complex multiplier
    useBitReverse   : Boolean,               // include bit reversal stage so that both input and output streaming data are in natural order
    minSRAMdepth    : Int                    // use SRAM for the delay line larger than minSRAMdepth
 ) {
  // Allowed values for some parameters
  final val allowedFftTypes      = Seq("sdf") //for future improvements it is open to add new fft types
  final val allowedDecimTypes    = Seq(DITDecimType, DIFDecimType)
  final val allowedSDFRadices    = Seq("2","2^2")
  
  // Common require functions used in FFT blocks
  def checkNumPointsPow2() {
    require(isPow2(numPoints), "number of points must be a power of 2")
  }
  def checkFftType() {
    require(allowedFftTypes.contains(fftType), s"""FFT type must be one of the following: ${allowedFftTypes.mkString(", ")}""")
  }
//   def checkDecimType() {
//     require(allowedDecimTypes.contains(decimType), s"""Decimation type must be one of the following: ${allowedDecimTypes.mkString(", ")}""")
//   }
  def checkSDFRadix() {
    require(allowedSDFRadices.contains(sdfRadix), s"""Radix must be one of the following: ${allowedSDFRadices.mkString(", ")}""")
  }
  
  // muxes can not accept nonequal data types beccause of that only specific stages can support grow logic
  def checkExpandLogic() {
    //used only for radix 2^2 and full run time configurability
    if (decimType == DIFDecimType || (decimType == DITDecimType && (expandLogic.size % 2 == 0))) {
      expandLogic.tail.zipWithIndex.collect{ case (e,i) if ((i+1) % 2) == 0 => e }.foreach { grow => require(grow == 0, "Inappropiate settings for growing logic!") }
    }
    else {
      expandLogic.tail.zipWithIndex.collect{ case (e,i) if ((i % 2) == 0) => e }.foreach { grow => require(grow == 0, "Inappropiate settings for growing logic!") }
    }
    /*see example for growing settings in the case that user want to achieve the highest posible growing factor
      DIF :
      numPoints = 1024 - expandLogic = Array(1,1,0,1,0,1,0,1,0,1)
      numPoints = 512  - expandLogic = Array(1,1,0,1,0,1,0,1,0)
      DIT :
      numPoints = 1024 - expandLogic = Array(1,1,0,1,0,1,0,1,0,1)
      numPoints = 512  - expandLogic = Array(1,0,1,0,1,0,1,0,1)
    */
  }
  // combinational loop occurs for pipeline = 0 and radix 2^2 module with full run time configurability
  def checkPipeline() {
    require(numAddPipes!=0 | numMulPipes!=0, s"This design requires number of pipeline registers to be at least one")
  }
}
//TODO: Think to rename runTime and allign everything 
object FFTParams {
  def fixed(dataWidth       : Int = 16,
            binPoint        : Int = 14,
            twiddleWidth    : Int = 16,
            numPoints       : Int = 2,
            keepMSBorLSBReg : Boolean = false,
            keepMSBorLSB    : Array[Boolean] = Array.fill(log2Up(2))(true),
            overflowReg     : Boolean = false,
            fftType         : String = "sdf",
            decimType       : DecimType = DIFDecimType,
            sdfRadix        : String = "2^2",
            runTimeR22      : Option[Boolean] = Some(false),
            expandLogic     : Array[Int] = Array.fill(log2Up(2))(0),
            runTime         : Boolean = false,
            trimType        : TrimType = RoundHalfUp,
            numAddPipes     : Int = 0,
            numMulPipes     : Int = 0,
            fftDir          : Boolean = true,
            fftDirReg       : Boolean = false,
            use4Muls        : Boolean = false,
            useBitReverse   : Boolean = false,
            minSRAMdepth    : Int = 0
            ): FFTParams[FixedPoint] = {
    val protoIQ      = DspComplex(FixedPoint(dataWidth.W, binPoint.BP))
    // to allow for 1, -1, j, and -j to be expressed.
    val protoTwiddle = DspComplex(FixedPoint(twiddleWidth.W, (twiddleWidth-2).BP))
    // protoIQs
    val protoIQstages = Array.fill(log2Up(numPoints))(protoIQ).zip(expandLogic.scanLeft(0)(_+_).tail).map {
	    case((protoIQ, expandLogic)) => {
        DspComplex(FixedPoint((protoIQ.real.getWidth + expandLogic).W, binPoint.BP))
      }
    }
    FFTParams(
      numPoints = numPoints,
      protoIQ  = protoIQ,
      protoTwiddle = protoTwiddle,
      expandLogic = expandLogic,
      protoIQstages = protoIQstages,
      fftType = fftType,
      keepMSBorLSB = keepMSBorLSB,
      keepMSBorLSBReg = keepMSBorLSBReg,
      overflowReg = overflowReg,
      decimType = decimType,
      sdfRadix = sdfRadix,
      runTime = runTime,
      trimType = trimType,
      runTimeR22 = runTimeR22,
      numAddPipes = numAddPipes,
      numMulPipes = numMulPipes,
      fftDir = fftDir,
      fftDirReg = fftDirReg,
      use4Muls = use4Muls,
      useBitReverse = useBitReverse,
      minSRAMdepth = minSRAMdepth
    )
  }
  // Golden model
  def DSPReal(dataWidth     : Int = 16,
            binPoint        : Int = 14,
            twiddleWidth    : Int = 16, 
            numPoints       : Int = 2,
            fftType         : String = "sdf",
            decimType       : DecimType = DIFDecimType,
            sdfRadix        : String = "2^2",
            keepMSBorLSBReg : Boolean = false,
            keepMSBorLSB    : Array[Boolean] = Array.fill(log2Up(2))(true),
            overflowReg     : Boolean = false,
            runTimeR22      : Option[Boolean] = Some(false),
            expandLogic     : Array[Int] = Array.fill(log2Up(2))(0),
            runTime         : Boolean = false,
            trimType        : TrimType = Convergent, 
            numAddPipes     : Int = 0,
            numMulPipes     : Int = 0,
            fftDir          : Boolean = true,
            fftDirReg       : Boolean = false,
            use4Muls        : Boolean = false,
            useBitReverse   : Boolean = false,
            minSRAMdepth    : Int = 0
): FFTParams[DspReal] = {
    val protoIQ      = DspComplex(new DspReal, new DspReal)
    // to allow for 1, -1, j, and -j to be expressed.
    val protoTwiddle = DspComplex(new DspReal, new DspReal)
    val protoIQstages = Array.fill(log2Up(numPoints))(protoIQ).zip(expandLogic.scanLeft(expandLogic(0))(_+_).tail).map {
	   case((protoIQ, expandLogic)) => DspComplex(new DspReal,new DspReal)
    }
    FFTParams(
      numPoints = numPoints,
      protoIQ  = protoIQ,
      protoTwiddle = protoTwiddle,
      expandLogic = expandLogic,
      protoIQstages = protoIQstages,
      fftType = fftType,
      keepMSBorLSB = keepMSBorLSB,
      keepMSBorLSBReg = keepMSBorLSBReg,
      overflowReg = overflowReg,
      decimType = decimType,
      sdfRadix = sdfRadix,
      numAddPipes = numAddPipes,
      numMulPipes = numMulPipes,
      runTimeR22 = runTimeR22,
      runTime = runTime,
      trimType = trimType,
      fftDir = fftDir,
      fftDirReg = fftDirReg,
      use4Muls = use4Muls,
      useBitReverse = useBitReverse,
      minSRAMdepth = minSRAMdepth
    )
  }
}


class FFTIO [T <: Data : Ring](params: FFTParams[T]) extends Bundle {
  val in = Flipped(Decoupled(params.protoIQ))
  val out = Decoupled(params.protoIQstages(log2Up(params.numPoints)-1))
  
  val lastOut = Output(Bool())
  val lastIn = Input(Bool())
  // control registers
  val fftSize = if (params.runTime) Some(Input(UInt((log2Up(params.numPoints)).W))) else None
  val keepMSBorLSBReg = if (params.keepMSBorLSBReg) Some(Input(Vec(log2Up(params.numPoints),Bool()))) else None
  val fftDirReg = if (params.fftDirReg) Some(Input(Bool())) else None
  //val flushDataOut = Input(Bool())
  
  // status registers
  val busy = Output(Bool())
  val overflow = if (params.overflowReg) Some(Output(Vec(log2Up(params.numPoints),Bool()))) else None
  
  override def cloneType: this.type = FFTIO(params).asInstanceOf[this.type]
}

object FFTIO {
  def apply[T <: Data : Ring](params: FFTParams[T]): FFTIO[T] = new FFTIO(params)
}

/**
 * Top level sdf core
 *
 * Instantiates the correct type of SDFFFT based on parameter value
 */

 
class SDFFFT[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  val io = IO(FFTIO(params))
  params.checkFftType()
  params.checkSDFRadix()
  //val name = "SDFFFT".concat(params.numPoints.toString) 
  override def desiredName = "SDFFFT_" + params.numPoints.toString + "_" + params.protoIQ.real.getWidth.toString
  params.sdfRadix match {
    case "2" => {
      val fft = Module(new SDFChainRadix2(params))
      if (params.useBitReverse) {
        if (params.decimType == DIFDecimType) {
          val paramsBR = BitReversePingPongParams(
            proto = params.protoIQstages.last,
            pingPongSize = params.numPoints,
            adjustableSize = params.runTime,
            bitReverseDir =  true
          )
          val bitReversal = Module(new BitReversePingPong(paramsBR))
          if (params.runTime) {
            bitReversal.io.size.get := 1.U << io.fftSize.get
          }
          fft.io.in <> io.in
          fft.io.lastIn := io.lastIn
          bitReversal.io.in <> fft.io.out
          bitReversal.io.lastIn <> fft.io.lastOut
          io.out <> bitReversal.io.out
          io.lastOut <> bitReversal.io.lastOut 
        }
        else {
          val paramsBR = BitReversePingPongParams(
            proto = params.protoIQstages.last,
            pingPongSize = params.numPoints,
            adjustableSize = params.runTime,
            bitReverseDir =  false
          )
          val bitReversal = Module(new BitReversePingPong(paramsBR))
          if (params.runTime) {
            bitReversal.io.size.get := 1.U << io.fftSize.get
          }
          bitReversal.io.in <> io.in
          bitReversal.io.lastIn := io.lastIn
          fft.io.in <> bitReversal.io.out
          fft.io.lastIn <> bitReversal.io.lastOut
          io.out <> fft.io.out
          io.lastOut <> fft.io.lastOut
        }
      }
      else {
        fft.io.in <> io.in
        fft.io.lastIn := io.lastIn
        io.lastOut := fft.io.lastOut
        io.out <> fft.io.out
      }
      io.busy := fft.io.busy
      if (params.runTime) {
        fft.io.fftSize.get := io.fftSize.get
      }
      if (params.overflowReg) {
        io.overflow.get := fft.io.overflow.get
      }
      if (params.keepMSBorLSBReg) {
        fft.io.keepMSBorLSBReg.get := io.keepMSBorLSBReg.get
      }
      if (params.fftDirReg) {
        fft.io.fftDirReg.get := io.fftDirReg.get
      }
    }
    case "2^2" => {
      val fft = if (params.runTimeR22.getOrElse(false) == true) Module(new SDFChainRadix22RunTime(params)) else Module(new SDFChainRadix22(params))
      if (params.useBitReverse) {
        if (params.decimType == DIFDecimType) {
          val paramsBR = BitReversePingPongParams(
            proto = params.protoIQstages.last,
            pingPongSize = params.numPoints,
            adjustableSize = params.runTime,
            bitReverseDir =  true
          )
          val bitReversal = Module(new BitReversePingPong(paramsBR))
          if (params.runTime) {
            bitReversal.io.size.get := 1.U << io.fftSize.get
          }
          fft.io.in <> io.in
          fft.io.lastIn := io.lastIn
          bitReversal.io.in <> fft.io.out
          bitReversal.io.lastIn <> fft.io.lastOut
          io.out <> bitReversal.io.out
          io.lastOut <> bitReversal.io.lastOut 
        }
        else {
          val paramsBR = BitReversePingPongParams(
            proto = params.protoIQstages.last,
            pingPongSize = params.numPoints,
            adjustableSize = params.runTime,
            bitReverseDir =  false
          )
          val bitReversal = Module(new BitReversePingPong(paramsBR))
          if (params.runTime) {
            bitReversal.io.size.get := 1.U << io.fftSize.get
          }
          bitReversal.io.in <> io.in
          bitReversal.io.lastIn := io.lastIn
          fft.io.in <> bitReversal.io.out
          fft.io.lastIn <> bitReversal.io.lastOut
          io.out <> fft.io.out
          io.lastOut <> fft.io.lastOut
        }
      }
      else {
        fft.io.in <> io.in
        fft.io.lastIn := io.lastIn
        io.lastOut := fft.io.lastOut
        io.out <> fft.io.out
      }
      
      io.busy := fft.io.busy
      // optional ports/registers
      if (params.runTime) {
        fft.io.fftSize.get := io.fftSize.get
      }
      if (params.overflowReg) {
        io.overflow.get := fft.io.overflow.get
      }
      if (params.keepMSBorLSBReg) {
        fft.io.keepMSBorLSBReg.get := io.keepMSBorLSBReg.get
      }
      if (params.fftDirReg) {
        fft.io.fftDirReg.get := io.fftDirReg.get
      }
    }
  }
}

object Butterfly extends hasContext {
  def apply[T <: Data : Real : BinaryRepresentation](in: Seq[DspComplex[T]]): Seq[DspComplex[T]] = {
    require(in.length == 2, "2-point DFT only for no defined twiddle type")
    (Seq(DspContext.alter(DspContext.current.copy(overflowType = Grow, binaryPointGrowth = 0))
                            { in(0) context_+ in(1) },
                          DspContext.alter(DspContext.current.copy(overflowType = Grow, binaryPointGrowth = 0))
                            { in(0) context_- in(1) }))
   }
}

object SDFFFTApp extends App
{
  val params = FFTParams.fixed(
    dataWidth = 16,
    twiddleWidth = 16,
    numPoints = 1024,
    decimType = DIFDecimType,
    numAddPipes = 1,
    numMulPipes = 1,
    runTime = false,
    expandLogic = Array.fill(log2Up(1024))(0),
    keepMSBorLSB = Array.fill(log2Up(1024))(true),
    binPoint = 14,
    minSRAMdepth = 32
  )
 //chisel3.Driver.execute(args,()=>new SDFFFT(params))
  
  val arguments = Array(
    "-X", "verilog",
    "--repl-seq-mem", "-c:SDFChainRadix22:-o:mem.conf",
    "--log-level", "info"
  )
  // generate blackbox-es for memories
  (new ChiselStage).execute(arguments, Seq(ChiselGeneratorAnnotation(() =>new SDFFFT(params))))
}






