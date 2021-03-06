package com.johnsnowlabs.nlp.annotators

import com.johnsnowlabs.nlp.util.ConfigHelper
import com.johnsnowlabs.nlp.util.io.ResourceHelper
import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.annotators.common.{Tokenized, TokenizedSentence}
import com.typesafe.config.Config
import org.apache.spark.ml.param.{IntParam, Param}
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}

/**
  * Created by alext on 10/23/16.
  */

/**
  * Extracts entities out of provided phrases
  * @param uid internally required UID to make it writable
  * @@ entities: Unique set of phrases
  * @@ requireSentences: May use sentence boundaries provided by a previous SBD annotator
  * @@ maxLen: Auto limit for phrase lenght
  */
class EntityExtractor(override val uid: String) extends AnnotatorModel[EntityExtractor] {

  import com.johnsnowlabs.nlp.AnnotatorType._

  val maxLen: IntParam = new IntParam(this, "maxLen", "maximum phrase length")

  val entities: Param[String] = new Param(this, "entities", "set of entities (phrases)")
  private var loadedEntities: Array[Array[String]] = loadEntities

  override val annotatorType: AnnotatorType = ENTITY

  override val requiredAnnotatorTypes: Array[AnnotatorType] = Array(DOCUMENT)

  setDefault(inputCols, Array(DOCUMENT))

  /** internal constructor for writabale annotator */
  def this() = this(Identifiable.randomUID("ENTITY_EXTRACTOR"))

  def setEntities(value: String): this.type = {
    set(entities, value)
    loadedEntities = loadEntities
    this
  }

  def getEntities: Array[Array[String]] = loadedEntities

  def setMaxLen(value: Int): this.type = set(maxLen, value)

  def getMaxLen: Int = $(maxLen)

  /**
    * Loads entities from a provided source.
    */
  private def loadEntities: Array[Array[String]] = {
    val src = get(entities).map(path => EntityExtractor.retrieveEntityExtractorPhrases(path))
      .getOrElse(EntityExtractor.retrieveEntityExtractorPhrases())
    val tokenizer = new RegexTokenizer().setPattern("\\w+")
    val stemmer = new Stemmer()
    val normalizer = new Normalizer()
    val phrases: Array[Array[String]] = src.map {
      line =>
        val annotation = Seq(Annotation(line))
        val tokens = tokenizer.annotate(annotation)
        val stems = stemmer.annotate(tokens)
        val nTokens = normalizer.annotate(stems)
        nTokens.map(_.result).toArray
    }
    phrases
  }

  /**
    * matches entities depending on utilized annotators and stores them in the annotation
    * @param sentence pads annotation content to phrase limits
    * @param maxLen applies limit not to exceed results
    * @param entities entities to find within annotators results
    * @return
    */
  private def phraseMatch(sentence: TokenizedSentence, maxLen: Int, entities: Array[Array[String]]): Seq[Annotation] = {
    val tokens = sentence.indexedTokens
    tokens.padTo(tokens.length + maxLen - (tokens.length % maxLen), null).sliding(maxLen).flatMap {
      window =>
        window.filter(_ != null).inits.filter {
          phraseCandidate =>
            entities.contains(phraseCandidate.map(_.token))
        }.map {
          phrase =>
            Annotation(
              ENTITY,
              phrase.head.begin,
              phrase.last.end,
              phrase.map(_.token).mkString(" "),
              Map.empty[String, String]
            )
        }
    }.toSeq
  }

  /** Defines annotator phrase matching depending on whether we are using SBD or not */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val sentences = Tokenized.unpack(annotations)
    sentences.flatMap{ sentence =>
        phraseMatch(sentence, $(maxLen), loadedEntities)
      }
  }

}

object EntityExtractor extends DefaultParamsReadable[EntityExtractor] {

  private val config: Config = ConfigHelper.retrieve

  protected def retrieveEntityExtractorPhrases(
                                      entitiesPath: String = "__default",
                                      fileFormat: String = config.getString("nlp.entityExtractor.format")
                                    ): Array[String] = {
    val filePath = if (entitiesPath == "__default") config.getString("nlp.entityExtractor.file") else entitiesPath
    ResourceHelper.parseLinesText(filePath, fileFormat)
  }


}