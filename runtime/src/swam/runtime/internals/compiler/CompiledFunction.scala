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
package compiler

import cfg._
import cats.effect.IO
import swam.runtime.internals.interpreter.AsmInst

private[runtime] case class CompiledFunction[F[_]](idx: Int,
                                                   tpe: FuncType,
                                                   locals: Vector[ValType],
                                                   code: Array[AsmInst[F]],
                                               	   cfg: IO[CFG])
