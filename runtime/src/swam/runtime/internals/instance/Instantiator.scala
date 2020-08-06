/*
 * Copyright 2018 Lucas Satabin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swam
package runtime
package internals
package instance

import imports._
import compiler._
import trace._
import cfg._

import cats.effect._
import cats.implicits._
import swam.binary.custom.FunctionNames
import swam.runtime.internals.interpreter.{AsmInst, InstructionListener, Asm}

private[runtime] class Instantiator[F[_]](engine: Engine[F])(implicit F: Async[F]) {

  private val interpreter = engine.interpreter
  private val dataOnHeap = engine.conf.data.onHeap
  private val dataHardMax = engine.conf.data.hardMax
  private val def_undef_func:Set[String] = WasiFilter.readWasiFile()

  def instantiate(module: Module[F], imports: Imports[F]): F[Instance[F]] = {
    for {
      // check and order the imports
      imports <- check(module.imports, imports)
      // now initialize the globals
      globals <- initialize(module.globals, imports)
      // allocate the module
      instance <- allocate(module, globals, imports)
      // we now have a fresh instance with imports and exports all wired, and various memory areas allocated, time to initialize and start it
      _ <- instance.init
    } yield instance
  }

  private def check(mimports: Vector[Import], provided: Imports[F]): F[Vector[Interface[F, Type]]] =
    F.tailRecM((0, Vector.empty[Interface[F, Type]])) {
      case (idx, acc) =>
        if (idx >= mimports.size) {
          F.pure(Right(acc))
        } else {
          val imp = mimports(idx)
          provided.find(imp.moduleName, imp.fieldName).flatMap { provided =>
            if (provided.tpe <:< imp.tpe)
              F.pure(Left((idx + 1, acc :+ provided)))
            else
              F.raiseError(new LinkException(s"Expected import of type ${imp.tpe} but got ${provided.tpe}"))
          }
        }
    }

  private def initialize(globals: Vector[CompiledGlobal[F]],
                         imports: Vector[Interface[F, Type]]): F[Vector[GlobalInstance[F]]] = {
    val impglobals = imports.collect {
      case g: Global[F] => g
    }
    val inst = new Instance[F](null, interpreter)
    inst.globals = impglobals
    F.tailRecM((0, Vector.empty[GlobalInstance[F]])) {
      case (idx, acc) =>
        if (idx >= globals.size)
          F.pure(Right(acc))
        else
          globals(idx) match {
            case CompiledGlobal(tpe, init) =>
              interpreter
                .interpretInit(tpe.tpe, init, inst)
                .flatMap[Long] {
                  case Vector(res) => F.pure(res)
                  case res =>
                    F.raiseError(
                      new LinkException(s"Global expression must return a single result but got ${res.size}"))
                }
                .flatMap { res =>
                  val i = new GlobalInstance[F](tpe)
                  i.rawset(res)
                  F.pure(Left((idx + 1, acc :+ i)))
                }
          }
    }
  }

  private def allocate(module: Module[F],
                       globals: Vector[GlobalInstance[F]],
                       imports: Vector[Interface[F, Type]]): F[Instance[F]] = {

    val instance = new Instance[F](module, interpreter)

    val (ifunctions, iglobals, itables, imemories) = imports.foldLeft(
      (Vector.empty[Function[F]], Vector.empty[Global[F]], Vector.empty[Table[F]], Vector.empty[Memory[F]])) {
      case ((ifunctions, iglobals, itables, imemories), f: Function[F]) =>
        (ifunctions :+ f, iglobals, itables, imemories)
      case ((ifunctions, iglobals, itables, imemories), g: Global[F]) =>
        (ifunctions, iglobals :+ g, itables, imemories)
      case ((ifunctions, iglobals, itables, imemories), t: Table[F]) =>
        (ifunctions, iglobals, itables :+ t, imemories)
      case ((ifunctions, iglobals, itables, imemories), m: Memory[F]) =>
        (ifunctions, iglobals, itables, imemories :+ m)
    }
    //println(module.functions)
    instance.funcs = ifunctions ++ module.functions.map {
      case CompiledFunction(typeIndex, tpe, locals, code, cfg) =>
        val functionName =
          module.names.flatMap(_.subsections.collectFirstSome {
            case FunctionNames(names) =>
              {
                names.get(typeIndex)
              }
            case _ =>
              None
          })
        val toWrap1 = engine.instructionListener match { 
          case Some(listener) => {
            var index = -1
            var index_brif_return = -1
            var index_global = -1
            var current = 0
            //var next = 0

            val fn = functionName.getOrElse("N/A").toString
            if(!listener.wasiCheck){
              // code with Wasi methods fails to get the cfg. 
              // So currently not in scope. Todo later.
              code
            }
            else{
              //Working code of cfg for some Wasm modules. 
              //So trying to code path coverage for those wasm modules.
              if(listener.filter.equals(".")){
                if(!def_undef_func.contains(fn)) {
                  println(fn)
                  val f_cfg = Blocker[IO].use { blocker => 
                    for {
                      cf <- cfg
                    } yield cf
                  }.unsafeRunSync()
                  //println("CFG in Instantiator" + f_cfg.blocks.map(x => println(s"This is a basic block ${x.id} :: " + x)))
                  val newCode = code.map{c => if(c.toString.contains("BrIf@") || c.toString.contains("Br@") || c.toString.contains("Return")){
                      index_brif_return = index_brif_return + 1
                    }
                    else{
                      index = index + 1
                    } 
                    index_global = index_global + 1
                    (c, index, index_global) 
                  }
                  newCode.map{
                    case(c, id, ig) => {
                     f_cfg.blocks.map(f => {
                        val checkLast = f.stmts.lastOption
                        current = f.id
                        checkLast match {
                          case Some(x) => {
                            //println(s"This block is Some : ${f.id}, ${f.jump}, $x") 
                            if(x._2 == id){
                                //println(s"This block is Some : ${f.id}, ${f.jump}, $x") 
                                val nextList = f.jump match {
                                    case Some(Jump.To(tgt)) => List(tgt) //println(s"This is a Jump : ::: ${tgt}")
                                    case Some(Jump.If(tTgt, eTgt)) => List(tTgt,eTgt)//println(s"This is a Jump : ::: ${tTgt},${eTgt}")
                                    case Some(Jump.Table(cases, dflt)) => cases.toList :+ dflt
                                    case None => Nil
                                }

                                nextList.map(x => {
                                  code(ig) = new engine.asm.InstructionWrapper(c, id, current, x, listener, functionName).asInstanceOf[AsmInst[F]]  
                                })
                                //println(s"This is next_code + ${next_code}")
                            }
                          }
                          case None => {
                            val nextList = f.jump match {
                                    case Some(Jump.To(tgt)) => List(tgt) //println(s"This is a Jump : ::: ${tgt}")
                                    case Some(Jump.If(tTgt, eTgt)) => List(tTgt,eTgt)//println(s"This is a Jump : ::: ${tTgt},${eTgt}")
                                    case Some(Jump.Table(cases, dflt)) => cases.toList :+ dflt
                                    case None => Nil
                            }
                            nextList.map(x => {
                              new engine.asm.InstructionWrapper(c, id, current, x, listener, functionName).asInstanceOf[AsmInst[F]]  
                            })

                          }
                        }
                      })
                    }
                  }
                  code
                }
                else code 
              }
              else {
                if(!def_undef_func.contains(fn)) {
                  if(WasiFilter.checkPattern(listener.filter, fn)) code 
                    else {
                        code.zipWithIndex.map{
                          //case (c, index) => new engine.asm.InstructionWrapper(c, index, listener, functionName).asInstanceOf[AsmInst[F]]
                          case(c,index) => c
                        }
                      }
                  }
                  else code
                }
              }
            }
          case None => code
        } 
        /*val toWrap = engine.instructionListener match {
          case Some(listener) => {
            // TODO change functionName to some kind of "debugging" class
            /**
             * @author Javier Cabrera-Arteaga on 2020-07-16
             * Changed the implementation to Add index to each and every instruction.
             */
            val fn = functionName.getOrElse("N/A").toString
            code.zipWithIndex.map{case (c, index) => 
              if(!listener.wasiCheck){
                /*if(listener.filter.equals("."))
                  new engine.asm.InstructionWrapper(c, index, listener, functionName).asInstanceOf[AsmInst[F]]
                else{  
                  if(WasiFilter.checkPattern(listener.filter, fn)) c 
                  else new engine.asm.InstructionWrapper(c, index, listener, functionName).asInstanceOf[AsmInst[F]]
                }*/

                //No need of reading the cfgs for the Wasi methods.
                c
              }
              else{
                if(listener.filter.equals(".")){
                  if(!def_undef_func.contains(fn)) {
                    println(fn)
                    val ca = Blocker[IO].use { blocker => 
                      for {
                        cf <- cfg
                      } yield cf
                    }.unsafeRunSync()
                    println("CFG in Instantiator" + ca.blocks)
                    new engine.asm.InstructionWrapper(c, index, listener, functionName).asInstanceOf[AsmInst[F]]
                  }
                  else c
                  }
                else {
                  if(!def_undef_func.contains(fn)) {
                    if(WasiFilter.checkPattern(listener.filter, fn)) c 
                    else new engine.asm.InstructionWrapper(c, index, listener, functionName).asInstanceOf[AsmInst[F]]
                  }
                  else c
                }  
              }
            }
          }
          case None => code
        }*/
        new FunctionInstance[F](tpe, locals, toWrap1, instance, functionName)
    }

    //println(module.exports.map(_).toMap)
    instance.globals = iglobals ++ globals
    instance.tables = itables ++ module.tables.map {
      case TableType(_, limits) => new TableInstance[F](limits.min, limits.max)
    }
    instance.memories = imemories ++ module.memories.map {
      case MemType(limits) => new MemoryInstance[F](limits.min, limits.max, dataOnHeap, dataHardMax.bytes.toInt)
    }
    // trace memory acceses if tracer exists
    engine.tracer.foreach { tracer => instance.memories = instance.memories.map(TracingMemory(_, tracer)) }
    instance.exps = module.exports.map {
      case Export.Function(name, tpe, idx) => (name, instance.funcs(idx))
      case Export.Global(name, tpe, idx)   => (name, instance.globals(idx))
      case Export.Table(name, tpe, idx)    => (name, instance.tables(idx))
      case Export.Memory(name, tpe, idx)   => (name, instance.memories(idx))
    }.toMap
    F.pure(instance)
  }

}
