package swam
package cli

import java.nio.file.{Path, Paths, StandardOpenOption}
import java.util.concurrent.TimeUnit
import java.util.logging.{LogRecord, Formatter => JFormatter}

import cats.effect.{Blocker, Clock, ExitCode, IO}
import cats.implicits._
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.enumeratum._

import io.odin.formatter.Formatter
import io.odin.formatter.options.ThrowableFormat
import io.odin.{Logger, consoleLogger}
import fs2._
import swam.ValType.{F32, F64, I32, I64}
import swam.binary.ModuleStream
import swam.decompilation._
import swam.code_analysis.coverage.{CoverageListener, CoverageReporter}
import swam.runtime.imports._
import swam.runtime.trace._
import swam.runtime.wasi.{Wasi, WasiOption}
import swam.runtime.{Engine, Function, Module, Value}
import swam.text.Compiler
import swam.binary.custom.{FunctionNames, ModuleName}
import swam.cli.Main.wasiOption
import swam.runtime.internals.compiler.CompiledFunction

private object NoTimestampFormatter extends JFormatter {
  override def format(x: LogRecord): String =
    x.getMessage
}

object Main extends CommandIOApp(name = "swam-cli", header = "Swam from the command line") {

  type AsIIO[T] = AsInterface[T, IO]
  type AsIsIO[T] = AsInstance[T, IO]

  ////// CLI-COMMAND ARGUMENTS //////
  // Beware (Opts.argument != Opts.arguments) && (Opts.option != Opts.options) && (Opts.flag != Opts.flags)

  val wasmFile =
    Opts.argument[Path](metavar = "wasm")

  // Arguments that get passed to the WASM code you execute. They are available through WASI args_get.
  val restArguments =
    Opts.arguments[String](metavar = "args").orEmpty

  val dirs = Opts
    .options[Path]("dir", "Preopen directory", short = "D", metavar = "dir")
    .orEmpty

  val mainFun =
    Opts
      .option[String]("main", "Execute function of provided name (default is _start)", short = "m")
      .withDefault("_start")

  val wat =
    Opts.flag("wat", "Input file is in WAT format, and needs to be parsed as such (default false)", short = "w").orFalse

  val wasi =
    Opts.flag("wasi", "Program is using wasi (default false)", short = "W").orFalse

  val time =
    Opts.flag("time", "Measure execution time (default false)", short = "C").orFalse

  val noValidateBinary =
    Opts.flag("novalidate", "Do not validate WASM binary, imports for example").orFalse

  val trace =
    Opts.flag("trace", "Trace WASM execution channels (default false)", short = "t").orFalse

  val exportInstrumented =
    Opts
      .option[Path]("export-instrumented", "Compile and export the instrumented WASM binary")
      .withDefault(null)
  /*val cov =
    Opts.flag("instcov", "Run the WebAssembly module and gets coverage.", short = "v").orFalse*/

  val covfilter = Opts.flag("cov-filter", "Generate coverage with filter on Wasi Methods", short = "r").orFalse

  val covOut = Opts
    .option[Path]("covout", "Output folder for coverage reports and show-map", short = "c")
    .withDefault(Paths.get(".").toAbsolutePath.normalize)

  val wasiOption = Opts
    .option[WasiOption]("wasi-option", "WASI options")
    .withDefault(WasiOption.NonBlockingRNG)

  val filter =
    Opts
      .option[String](
        "filter",
        "Filter the traces. The parameter is a regular expression applied to the opcode, e.g.: 'mread|mwrite' (default *)",
        short = "f")
      .withDefault("*")

  val traceFile =
    Opts
      .option[Path](
        "trace-file",
        "The file to which traces are written (default trace.log)",
        short = "l"
      )
      .withDefault(Paths.get("trace.log"))

  val debug =
    Opts.flag("debug", "Generate debug elements when compiling wat format (default false)", short = "d").orFalse

  val dev =
    Opts.flag("exceptions", "Print exceptions with stacktrace (default false)", short = "X").orFalse

  val textual =
    Opts.flag("wat", "Decompile in wat format (requires module to be valid) (default false)", short = "w").orFalse

  val out = Opts
    .option[Path]("out", "Save decompiled result in the given file. Prints to stdout if not provider", short = "o")

  val readChunkSizeOpt = Opts.option[Int]("chunk-size", "Decoder chunk size").withDefault(1 << 10)

  ////// CLI-COMMAND ARGUMENT COMBINATIONS //////

  val runOpts: Opts[Options] = Opts.subcommand("run", "Run a WebAssembly file") {
    (mainFun, wat, wasi, time, dirs, trace, traceFile, filter, debug, wasmFile, restArguments, wasiOption)
      .mapN { (main, wat, wasi, time, dirs, trace, traceFile, filter, debug, wasm, args, wasiOption) =>
        Run(wasm, args, main, wat, wasi, time, trace, filter, traceFile, dirs, debug, wasiOption)
      }
  }

  val covOpts: Opts[Options] = Opts.subcommand("coverage", "Run a WebAssembly file and generate coverage report") {
    (mainFun, wat, wasi, time, dirs, trace, traceFile, filter, debug, wasmFile, restArguments, exportInstrumented, covOut, covfilter, wasiOption, noValidateBinary).mapN {
      (main, wat, wasi, time, dirs, trace, traceFile, filter, debug, wasm, args, exportInstrumented, covOut, covfilter, wasiOption, noValidateBinary) =>
        WasmCov(wasm, args, main, wat, wasi, time, trace, filter, traceFile, dirs, debug, exportInstrumented, covOut, covfilter, wasiOption, noValidateBinary)
    }
  }

  val decompileOpts: Opts[Options] = Opts.subcommand("decompile", "Decompile a wasm file") {
    (textual, wasmFile, out.orNone, readChunkSizeOpt).mapN { (textual, wasm, out, chunkSize) =>
      Decompile(wasm, textual, out, chunkSize)
    }
  }

  val inferOpts: Opts[Options] =
    Opts.subcommand("infer", "Get the parameters type for functions file in Wasm module.") {
      (wasmFile, wat, mainFun).mapN { (wasm, wat, main) => Infer(wasm, wat, main) }
    }

  val validateOpts: Opts[Options] = Opts.subcommand("validate", "Validate a wasm file") {
    (wasmFile, wat, dev, readChunkSizeOpt).mapN(Validate)
  }

  val compileOpts: Opts[Options] = Opts.subcommand("compile", "Compile a wat file to wasm") {
    (wasmFile, out, debug).mapN(Compile)
  }

  val outFileOptions = List(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

  def main: Opts[IO[ExitCode]] =
    runOpts
      .orElse(covOpts)
      .orElse(inferOpts)
      .orElse(decompileOpts)
      .orElse(validateOpts)
      .orElse(compileOpts)
      .map { opts =>
        Blocker[IO].use { blocker =>
          opts match {
            case Run(file, args, main, wat, wasi, time, trace, filter, tracef, dirs, debug, wasiOption) =>
              for {
                tracer <- if (trace)
                  JULTracer[IO](blocker,
                    traceFolder = ".",
                    traceNamePattern = tracef.toAbsolutePath().toString(),
                    filter = filter,
                    formatter = NoTimestampFormatter).map(Some(_))
                else
                  IO(None)
                engine <- Engine[IO](blocker, tracer)
                tcompiler <- Compiler[IO](blocker)
                module = if (wat) tcompiler.stream(file, debug, blocker) else engine.sections(file, blocker)
                compiled <- engine.compile(module)
                wasmArgTypes <- inferSignature(compiled, main)
                argsParsed <- IO(createInputVector(wasmArgTypes, args))
                preparedFunction <- prepareFunction(compiled, wasiOption, main, dirs, args, wasi, blocker)

                _ <- IO(executeFunction(IO(preparedFunction), argsParsed, time))
              } yield ExitCode.Success

            // TODO: Remove this and instead to coverage flag in Run(...)
            case WasmCov(file, args, main, wat, wasi, time, trace, filter, tracef, dirs, debug, exportInstrumented, covOut, covfilter, wasiOption, noValidateBinary) =>
              for {
                tracer <- if (trace)
                  JULTracer[IO](blocker,
                    traceFolder = ".",
                    traceNamePattern = tracef.toAbsolutePath().toString(),
                    filter = filter,
                    formatter = NoTimestampFormatter).map(Some(_))
                else
                  IO(None)
                coverageListener = CoverageListener[IO](covfilter)
                engine <- Engine[IO](blocker, tracer, listener = Option(coverageListener))

                tcompiler <- swam.text.Compiler[IO](blocker)
                module = if (wat) tcompiler.stream(file, debug, blocker) else engine.sections(file, blocker)

                _ <- if (exportInstrumented != null) {

                  for {
                    _ <- (Stream.emits(ModuleStream.header.toArray) ++ module
                      .through(coverageListener.instrument)
                      .through(ModuleStream.encoder.encode[IO])
                      .flatMap(bv => Stream.emits(bv.toByteArray)))
                      .through(fs2.io.file.writeAll(exportInstrumented, blocker, outFileOptions))
                      .compile
                      .drain
                  } yield ExitCode.Success

                } else {

                  for {
                    compiled <- if (noValidateBinary) engine.compileNotValidate(module)
                    else engine.compile(module) // This is not needed since the validation is read from the config files
                    wasmArgTypes <- inferSignature(compiled, main)
                    argsParsed <- IO(createInputVector(wasmArgTypes, args))
                    preparedFunction <- prepareFunction(compiled, wasiOption, main, dirs, args, wasi, blocker)
                    _ <- IO(executeFunction(IO(preparedFunction), argsParsed, time))
                    _ <- IO(CoverageReporter.blockCoverage(covOut, file, coverageListener))
                  } yield ExitCode.Success

                }

              } yield ExitCode.Success

            case Decompile(file, textual, out, chunkSize) =>
              for {
                decompiler <- if (textual)
                  TextDecompiler[IO](blocker)
                else
                  RawDecompiler[IO]
                doc <- decompiler.decompilePath(file, blocker)
                outPipe = out.fold(fs2.io.stdout[IO](blocker))(fs2.io.file.writeAll[IO](_, blocker, outFileOptions))
                _ <- Stream
                  .emits(doc.render(10).getBytes("utf-8"))
                  .through(outPipe)
                  .compile
                  .drain
              } yield ExitCode.Success

            case Validate(file, wat, dev, chunkSize) =>
              val throwableFormat =
                if (dev)
                  ThrowableFormat(ThrowableFormat.Depth.Full, ThrowableFormat.Indent.Fixed(2))
                else
                  ThrowableFormat(ThrowableFormat.Depth.Fixed(0), ThrowableFormat.Indent.NoIndent)
              val formatter = Formatter.create(throwableFormat, true)
              val logger = consoleLogger[IO](formatter = formatter)
              for {
                engine <- Engine[IO](blocker)
                tcompiler <- Compiler[IO](blocker)
                module = if (wat) tcompiler.stream(file, false, blocker) else engine.sections(file, blocker)
                res <- engine.validate(module).attempt
                _ <- res.fold(t => logger.error("Module is invalid", t), _ => logger.info("Module is valid"))
              } yield ExitCode.Success

            case Infer(file, wat, main) =>
              for {
                engine <- Engine[IO](blocker)
                tcompiler <- swam.text.Compiler[IO](blocker)
                module = if (wat) tcompiler.stream(file, false, blocker) else engine.sections(file, blocker)
                compiled <- engine.compile(module)
                functionArgTypes <- inferSignature(compiled, main)
                params <- IO(functionArgTypes.map {
                  case I32 => "Int32"
                  case I64 => "Int64"
                  case F32 => "Float32"
                  case F64 => "Float64"
                })
                _ <- IO(println(params.mkString(",")))
              } yield ExitCode.Success

            case Compile(file, out, debug) =>
              for {
                tcompiler <- Compiler[IO](blocker)
                _ <- (Stream.emits(ModuleStream.header.toArray) ++ tcompiler
                  .stream(file, debug, blocker)
                  .through(ModuleStream.encoder.encode[IO])
                  .flatMap(bv => Stream.emits(bv.toByteArray)))
                  .through(fs2.io.file.writeAll(out, blocker, outFileOptions))
                  .compile
                  .drain
              } yield ExitCode.Success

          }
        }
      }

  def inferSignature(compiled: Module[IO], funcName: String): IO[Vector[ValType]] = {
    for {
      allFuncsOpt <- IO(compiled.names.flatMap(_.subsections.collectFirst { case FunctionNames(n) => n }))
      signature <- IO(
        allFuncsOpt match {
          case None => throw new Exception("The module does not contain a name/metadata section")
          case Some(allFuncs) => {
            val matchingFuncs = allFuncs.filter { case (_, name) => funcName == name }
            if (matchingFuncs.isEmpty) {
              System.err.println(s"Function '$funcName' does not exist. Listing available functions...")
              val functionsAvailable = allFuncs.map{case (_, value) => value}.mkString("\t")
              throw new Exception(s"Function '$funcName' does not exist. Available functions: $functionsAvailable")
            } else {
              if (matchingFuncs.size > 1) {
                System.err.println(s"Warning $funcName has more than one definition, taking the first one")
              }
              val funcIdx = matchingFuncs.collectFirst { case (tid, _) => tid }.get

              // There is always one at this point
              val funcType = compiled.functions.filter (f => f.idx == funcIdx) (0).tpe

              funcType.params
            }
          }
        }
      )
    } yield signature
  }

  // Create the required input vector for the instantiated Wasm function
  def createInputVector(argsTypes: Vector[ValType], args: List[String]): Vector[Value] = {
    if (argsTypes.length != args.length)
      throw new Exception("Number of args not equal to number of arg types!")
    argsTypes.zipWithIndex.map {
      case (I32, index) =>
        Value.Int32(args(index).toInt)
      case (I64, index) =>
        Value.Int64(args(index).toLong)
      case (F32, index) =>
        Value.Float32(args(index).toFloat)
      case (F64, index) =>
        Value.Float64(args(index).toDouble)
    }
  }

  def prepareFunction(module: Module[IO],
                      wasiOption: WasiOption,
                      functionName: String,
                      preopenedDirs: List[Path],
                      args: List[String],
                      useWasi: Boolean,
                      blocker: Blocker): IO[Function[IO]] = {
    val logger = consoleLogger[IO]()
    if (useWasi) {
      Wasi[IO](List(wasiOption), preopenedDirs, functionName :: args, logger, blocker).use { wasi =>
        for {
          instance <- module.importing("wasi_snapshot_preview1", wasi).instantiate
          exportedFunc <- instance.exports.function(functionName)
          memory <- instance.exports.memory("memory")
          _ <- wasi.mem.complete(memory)

        } yield exportedFunc
      }
    } else {
      for {
        instance <- module.instantiate
        exportedFunc <- instance.exports.function(functionName)
      } yield exportedFunc
    }
  }

  def executeFunction(preparedFunction: IO[Function[IO]], parameters: Vector[Value], time: Boolean): Vector[Value] = {
    val logger = consoleLogger[IO]()
    Blocker[IO]
      .use { _ =>
        for {
          f <- preparedFunction
          res <- if (time) measureTime(logger, f.invoke(parameters, None)) else f.invoke(parameters, None)
        } yield res
      }
      .unsafeRunSync()
  }

  def measureTime[T](logger: Logger[IO], io: IO[T]): IO[T] =
    for {
      start <- Clock[IO].monotonic(TimeUnit.NANOSECONDS)
      res <- io
      end <- Clock[IO].monotonic(TimeUnit.NANOSECONDS)
      _ <- logger.info(s"Execution took ${end - start}ns")
    } yield res
}
