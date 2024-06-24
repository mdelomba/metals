package scala.meta.internal.mtags

import java.io.UncheckedIOException
import java.nio.CharBuffer
import java.util.logging.Level
import java.util.logging.Logger

import scala.util.Properties
import scala.util.control.NonFatal

import scala.meta.Dialect
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.io.PlatformFileIO
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

final case class SymbolLocation(
    path: AbsolutePath,
    range: Option[s.Range]
)

/**
 * Index split on buckets per dialect in order to have a constant time
 * and low memory footprint to infer dialect for SymbolDefinition because
 * it's used in WorkspaceSymbolProvider
 *
 * @param toplevels keys are non-trivial toplevel symbols and values are the file
 *                  the symbols are defined in.
 * @param definitions keys are global symbols and the values are the files the symbols
 *                    are defined in. Difference between toplevels and definitions
 *                    is that toplevels contains only symbols generated by ScalaToplevelMtags
 *                    while definitions contains only symbols generated by ScalaMtags.
 */
class SymbolIndexBucket(
    toplevels: AtomicTrieMap[String, Set[AbsolutePath]],
    definitions: AtomicTrieMap[String, Set[SymbolLocation]],
    sourceJars: OpenClassLoader,
    toIndexSource: AbsolutePath => AbsolutePath = identity,
    mtags: Mtags,
    dialect: Dialect
) {

  private val logger = Logger.getLogger(classOf[SymbolIndexBucket].getName)

  def close(): Unit = sourceJars.close()

  def addSourceDirectory(
      dir: AbsolutePath
  ): List[IndexingResult] = {
    if (sourceJars.addEntry(dir.toNIO)) {
      dir.listRecursive.toList.flatMap {
        case source if source.isScala =>
          addSourceFile(source, Some(dir), isJava = false)
        case _ =>
          None
      }
    } else List.empty
  }

  def addSourceJar(
      jar: AbsolutePath
  ): List[IndexingResult] = {
    if (sourceJars.addEntry(jar.toNIO)) {
      FileIO.withJarFileSystem(jar, create = false) { root =>
        try {
          root.listRecursive.toList.flatMap {
            case source if source.isScala =>
              addSourceFile(source, None, isJava = false)
            case source if source.isJava =>
              addSourceFile(source, None, isJava = true)
            case _ =>
              None
          }
        } catch {
          // this happens in broken jars since file from FileWalker should exists
          case _: UncheckedIOException => Nil
        }
      }
    } else
      List.empty
  }

  def addIndexedSourceJar(
      jar: AbsolutePath,
      symbols: List[(String, AbsolutePath)]
  ): Unit = {
    if (sourceJars.addEntry(jar.toNIO)) {
      symbols.foreach { case (sym, path) =>
        toplevels.updateWith(sym) {
          case Some(acc) => Some(acc + path)
          case None => Some(Set(path))
        }
      }
    }
    PlatformFileIO.newJarFileSystem(jar, create = false)
  }

  def addSourceFile(
      source: AbsolutePath,
      sourceDirectory: Option[AbsolutePath],
      isJava: Boolean
  ): Option[IndexingResult] = {
    val IndexingResult(path, topLevels, overrides) =
      indexSource(source, dialect, sourceDirectory, isJava)
    topLevels.foreach { symbol =>
      toplevels.updateWith(symbol) {
        case Some(acc) => Some(acc + source)
        case None => Some(Set(source))
      }
    }
    Some(IndexingResult(path, topLevels, overrides))
  }

  private def indexSource(
      source: AbsolutePath,
      dialect: Dialect,
      sourceDirectory: Option[AbsolutePath],
      isJava: Boolean
  ): IndexingResult = {
    val uri = source.toIdeallyRelativeURI(sourceDirectory)
    val (doc, overrides) = mtags.indexWithOverrides(source, dialect)
    val sourceTopLevels =
      doc.occurrences.iterator
        .filterNot(_.symbol.isPackage)
        .map(_.symbol)
    val topLevels =
      if (source.isAmmoniteScript) sourceTopLevels.toList
      else if (isJava) {
        sourceTopLevels.toList.headOption
          .filter(sym => !isTrivialToplevelSymbol(uri, sym, "java"))
          .toList
      } else {
        sourceTopLevels
          .filter(sym => !isTrivialToplevelSymbol(uri, sym, "scala"))
          .toList
      }
    IndexingResult(source, topLevels, overrides)
  }

  // Returns true if symbol is com/foo/Bar# and path is /com/foo/Bar.scala
  // Such symbols are "trivial" because their definition location can be computed
  // on the fly.
  private def isTrivialToplevelSymbol(
      path: String,
      symbol: String,
      extension: String = "scala"
  ): Boolean = {
    val pathBuffer =
      CharBuffer.wrap(path).subSequence(1, path.length - extension.length - 1)
    val symbolBuffer =
      CharBuffer.wrap(symbol).subSequence(0, symbol.length - 1)
    pathBuffer.equals(symbolBuffer)
  }

  def addToplevelSymbol(
      path: String,
      source: AbsolutePath,
      toplevel: String
  ): Unit = {
    if (source.isAmmoniteScript || !isTrivialToplevelSymbol(path, toplevel)) {
      toplevels.updateWith(toplevel) {
        case Some(acc) => Some(acc + source)
        case None => Some(Set(source))
      }
    }
  }

  def query(symbol: Symbol): List[SymbolDefinition] =
    query0(symbol, symbol)

  /**
   * Returns the file where symbol is defined, if any.
   *
   * Uses two strategies to recover from missing symbol definitions:
   * - try to enter the toplevel symbol definition, then lookup symbol again.
   * - if the symbol is synthetic, for examples from a case class of macro annotation,
   *  fall back to related symbols from the enclosing class, see `DefinitionAlternatives`.
   *
   * @param querySymbol The original symbol that was queried by the user.
   * @param symbol The symbol that
   * @return
   */
  private def query0(
      querySymbol: Symbol,
      symbol: Symbol
  ): List[SymbolDefinition] = {

    removeOldEntries(symbol)

    if (!definitions.contains(symbol.value)) {
      // Fallback 1: enter the toplevel symbol definition
      val toplevel = symbol.toplevel
      val files = toplevels.get(toplevel.value)
      files match {
        case Some(files) =>
          files.foreach(addMtagsSourceFile(_))
        case _ =>
          loadFromSourceJars(trivialPaths(toplevel))
            .orElse(loadFromSourceJars(modulePaths(toplevel)))
            .foreach(_.foreach(addMtagsSourceFile(_)))
      }
      if (!definitions.contains(symbol.value)) {
        // Fallback 2: try with files for companion class
        if (toplevel.value.endsWith(".")) {
          val toplevelAlternative = s"${toplevel.value.stripSuffix(".")}#"
          for {
            companionClassFile <- toplevels
              .get(toplevelAlternative)
              .toSet
              .flatten
            if (!files.exists(_.contains(companionClassFile)))
          } addMtagsSourceFile(companionClassFile)
        }
      }
    }
    if (!definitions.contains(symbol.value)) {
      // Fallback 3: guess related symbols from the enclosing class.
      DefinitionAlternatives(symbol)
        .flatMap(alternative => query0(querySymbol, alternative))
    } else {
      definitions
        .get(symbol.value)
        .map { paths =>
          paths.map { location =>
            SymbolDefinition(
              querySymbol = querySymbol,
              definitionSymbol = symbol,
              path = location.path,
              dialect = dialect,
              range = location.range,
              kind = None,
              properties = 0
            )
          }.toList
        }
        .getOrElse(List.empty)
    }
  }

  /**
   * Remove possible old, outdated entries from the toplevels and definitions.
   * This action is performed when a symbol is queried, to avoid returning incorrect results.
   */
  private def removeOldEntries(symbol: Symbol): Unit = {
    val exists =
      (toplevels.get(symbol.value).getOrElse(Set.empty) ++ definitions
        .get(symbol.value)
        .map(_.map(_.path))
        .getOrElse(Set.empty)).filter(_.exists)

    toplevels.updateWith(symbol.value) {
      case None => None
      case Some(acc) =>
        val updated = acc.filter(exists(_))
        if (updated.isEmpty) None
        else Some(updated)
    }

    definitions.updateWith(symbol.value) {
      case None => None
      case Some(acc) =>
        val updated = acc.filter(loc => exists(loc.path))
        if (updated.isEmpty) None
        else Some(updated)
    }
  }

  private def allSymbols(path: AbsolutePath): s.TextDocument = {
    val toIndexSource0 = toIndexSource(path)
    mtags.allSymbols(toIndexSource0, dialect)
  }

  // similar as addSourceFile except indexes all global symbols instead of
  // only non-trivial toplevel symbols.
  private def addMtagsSourceFile(
      file: AbsolutePath,
      retry: Boolean = true
  ): Unit = try {
    val docs: s.TextDocuments = PathIO.extension(file.toNIO) match {
      case "scala" | "java" | "sc" =>
        val document = allSymbols(file)
        s.TextDocuments(List(document))
      case _ =>
        s.TextDocuments(Nil)
    }
    if (docs.documents.nonEmpty) {
      addTextDocuments(file, docs)
    }
  } catch {
    case NonFatal(e) =>
      logger.log(Level.WARNING, s"Error indexing $file", e)
      if (retry) addMtagsSourceFile(file, retry = false)
  }

  // Records all global symbol definitions.
  private def addTextDocuments(
      file: AbsolutePath,
      docs: s.TextDocuments
  ): Unit = {
    docs.documents.foreach { document =>
      document.occurrences.foreach { occ =>
        if (occ.symbol.isGlobal && occ.role.isDefinition) {
          definitions.updateWith(occ.symbol) {
            case Some(acc) => Some(acc + SymbolLocation(file, occ.range))
            case None => Some(Set(SymbolLocation(file, occ.range)))
          }
        } else {
          // do nothing, we only care about global symbol definitions.
        }
      }
    }
  }

  // Returns the first path that resolves to a file.
  private def loadFromSourceJars(
      paths: List[String]
  ): Option[List[AbsolutePath]] = {
    paths match {
      case Nil => None
      case head :: tail =>
        sourceJars.resolveAll(head) match {
          case Nil => loadFromSourceJars(tail)
          case values => Some(values.map(AbsolutePath.apply))
        }
    }
  }

  // Returns relative file paths for trivial toplevel symbols, example:
  // Input:  scala/collection/immutable/List#
  // Output: scala/collection/immutable/List.scala
  //         scala/collection/immutable/List.java
  private def trivialPaths(toplevel: Symbol): List[String] = {
    val noExtension = toplevel.value.stripSuffix(".").stripSuffix("#")
    List(
      noExtension + ".scala",
      noExtension + ".java"
    )
  }

  private def modulePaths(toplevel: Symbol): List[String] = {
    if (Properties.isJavaAtLeast("9")) {
      val noExtension = toplevel.value.stripSuffix(".").stripSuffix("#")
      val javaSymbol = noExtension.replace("/", ".")
      for {
        cls <- sourceJars.loadClassSafe(javaSymbol).toList
        // note(@tgodzik) Modules are only available in Java 9+, so we need to invoke this reflectively
        module <- Option(
          cls.getClass().getMethod("getModule").invoke(cls)
        ).toList
        moduleName <- Option(
          module.getClass().getMethod("getName").invoke(module)
        ).toList
        file <- List(
          s"$moduleName/$noExtension.java",
          s"$moduleName/$noExtension.scala"
        )
      } yield file
    } else {
      Nil
    }
  }
}

object SymbolIndexBucket {

  def empty(
      dialect: Dialect,
      mtags: Mtags,
      toIndexSource: AbsolutePath => AbsolutePath
  ): SymbolIndexBucket =
    new SymbolIndexBucket(
      AtomicTrieMap.empty,
      AtomicTrieMap.empty,
      new OpenClassLoader,
      toIndexSource,
      mtags,
      dialect
    )

}
