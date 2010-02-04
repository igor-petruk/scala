/* NSC -- new Scala compiler
 * Copyright 2006-2010 LAMP/EPFL
 * @author  Martin Odersky
 */

// $Id$

package scala.tools.nsc
package util

import java.io.File
import java.net.URL
import java.util.StringTokenizer
import scala.util.Sorting

import scala.collection.mutable.{ListBuffer, ArrayBuffer, HashSet => MutHashSet}
import scala.tools.nsc.io.AbstractFile

import ch.epfl.lamp.compiler.msil.{Type => MSILType, Assembly}

/** Keeping the MSIL classpath code in its own file is important to make sure
 *  we don't accidentally introduce a dependency on msil.jar in the jvm.
 */

object MsilClassPath {
  def collectTypes(assemFile: AbstractFile) = {
    var res: Array[MSILType] = MSILType.EmptyTypes
    val assem = Assembly.LoadFrom(assemFile.path)
    if (assem != null) {
      // DeclaringType == null: true for non-inner classes
      res = assem.GetTypes() filter (_.DeclaringType == null)
      Sorting.stableSort(res, (t1: MSILType, t2: MSILType) => (t1.FullName compareTo t2.FullName) < 0)
    }
    res
  }
}

/**
 * A assembly file (dll / exe) containing classes and namespaces
 */
class AssemblyClassPath(types: Array[MSILType], namespace: String, val isOptimized: Boolean) extends ClassPath[MSILType] {
  def name = {
    val i = namespace.lastIndexOf('.')
    if (i < 0) namespace
    else namespace drop (i + 1)
  }

  def this(assemFile: AbstractFile, isOptimized: Boolean) {
    this(MsilClassPath.collectTypes(assemFile), "", isOptimized)
  }

  private lazy val first: Int = {
    var m = 0
    var n = types.length - 1
    while (m < n) {
      val l = (m + n) / 2
      val res = types(l).FullName.compareTo(namespace)
      if (res < 0) m = l + 1
      else n = l
    }
    if (types(m).FullName.startsWith(namespace)) m else types.length
  }

  lazy val classes = {
    val cls = new ListBuffer[ClassRep]
    var i = first
    while (i < types.length && types(i).Namespace.startsWith(namespace)) {
      // CLRTypes used to exclude java.lang.Object and java.lang.String (no idea why..)
      if (types(i).Namespace == namespace)
        cls += ClassRep(Some(types(i)), None)
      i += 1
    }
    cls.toList
  }

  lazy val packages = {
    val nsSet = new MutHashSet[String]
    var i = first
    while (i < types.length && types(i).Namespace.startsWith(namespace)) {
      val subns = types(i).Namespace
      if (subns.length > namespace.length) {
        // example: namespace = "System", subns = "System.Reflection.Emit"
        //   => find second "." and "System.Reflection" to nsSet.
        val end = subns.indexOf('.', namespace.length + 1)
        nsSet += (if (end < 0) subns
                  else subns.substring(0, end))
      }
      i += 1
    }
    for (ns <- nsSet.toList)
      yield new AssemblyClassPath(types, ns, isOptimized)
  }

  val sourcepaths: List[AbstractFile] = Nil

  override def toString() = "assembly classpath "+ namespace
}

/**
 * The classpath when compiling with target:msil. Binary files are represented as
 * MSILType values.
 */
class MsilClassPath(ext: String, user: String, source: String, val isOptimized: Boolean) extends MergedClassPath[MSILType] {
  protected val entries: List[ClassPath[MSILType]] = assembleEntries()
  override protected def nameOfBinaryRepresentation(binary: MSILType) = binary.Name

  private def assembleEntries(): List[ClassPath[MSILType]] = {
    import ClassPath._
    val etr = new ListBuffer[ClassPath[MSILType]]
    val names = new MutHashSet[String]

    // 1. Assemblies from -Xassem-extdirs
    for (dirName <- expandPath(ext, expandStar = false)) {
      val dir = AbstractFile.getDirectory(dirName)
      if (dir ne null) {
        for (file <- dir) {
          val name = file.name.toLowerCase
          if (name.endsWith(".dll") || name.endsWith(".exe")) {
            names += name
            etr += new AssemblyClassPath(file, isOptimized)
          }
        }
      }
    }

    // 2. Assemblies from -Xassem-path
    for (fileName <- expandPath(user, expandStar = false)) {
      val file = AbstractFile.getFile(fileName)
      if (file ne null) {
        val name = file.name.toLowerCase
        if (name.endsWith(".dll") || name.endsWith(".exe")) {
          names += name
          etr += new AssemblyClassPath(file, isOptimized)
        }
      }
    }

    def check(n: String) {
      if (!names.contains(n))
      throw new AssertionError("Cannot find assembly "+ n +
         ". Use -Xassem-extdirs or -Xassem-path to specify its location")
    }
    check("mscorlib.dll")
    check("scalaruntime.dll")

    // 3. Source path
    for (dirName <- expandPath(source, expandStar = false)) {
      val file = AbstractFile.getDirectory(dirName)
      if (file ne null) etr += new SourcePath[MSILType](file, isOptimized)
    }

    etr.toList
  }
}