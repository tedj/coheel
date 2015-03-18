package de.uni_potsdam.hpi.coheel.wiki

import de.uni_potsdam.hpi.coheel.programs.DataClasses.Link
import org.sweble.wikitext.engine.nodes.EngPage
import org.sweble.wikitext.engine.utils.DefaultConfigEnWp
import org.sweble.wikitext.parser.nodes._

import scala.StringBuilder
import scala.collection.immutable.Queue
import scala.collection.mutable
import org.sweble.wikitext.engine._
import scala.collection.JavaConversions._

class Extractor(wikiPage: WikiPage, surfaceRepr: String => String) {

	val config = DefaultConfigEnWp.generate()
	/**
	 * Internal class for processing a possible link.
	 * @param node The XML node.
	 * @param text The link's text.
	 * @param destination The link's destination.
	 */
	protected case class LinkWithNode(node: WtNode, var text: String, var destination: String) {
		def this(node: WtNode) = this(node, null, null)
	}

	val compiledWikiPage = getCompiledWikiPage(wikiPage)
	var links: Seq[Link] = _


	def extractAllLinks(filterEmptySurfaceRepr: Boolean = true): Seq[Link] = {
		val allLinks = extractLinks() // TODO: ++ extractAlternativeNames()
		if (filterEmptySurfaceRepr)
			allLinks.filter { link => link.surfaceRepr.nonEmpty }
		else
			allLinks
	}

	def extractLinks(): Seq[Link] = {
		val rootNode = compiledWikiPage
//		println(compiledWikiPage.toString)
		val links = extractLinks(rootNode)
		links
	}

	/**
	 * Extracts plain text, with all wiki markup removed.
	 * @return The plain text of the wikipage.
	 */
	def extractPlainText(): String = {
		val plainTextConverter = new PlainTextConverter(this)
		val page = compiledWikiPage
		plainTextConverter.go(page).asInstanceOf[String]
	}

	/**
	 * This searches for the first paragraph in the text, and returns all bold texts within that first paragraph.
	 * These are supposed to be alternative names for the entity.
	 * @return A list of alternative names
	 */
	def extractAlternativeNames(): Queue[Link] = {
		// The minimum number of characters for the first paragraph
		val MIN_PARAGRAPH_LENGTH = 20
		val rootNode = compiledWikiPage

		nodeIterator(rootNode) {
			case paragraph: WtParagraph =>
				val paragraphText = getText(paragraph)
				if (paragraphText.length > MIN_PARAGRAPH_LENGTH) {
					return extractBoldWordsFrom(paragraph)
				}
			case _ =>
		}
		Queue()
	}

	private def extractBoldWordsFrom(paragraph: WtParagraph): Queue[Link] = {
		var boldWords = Queue[String]()
		nodeIterator(paragraph) {
			case bold: WtBold =>
				val text = getText(bold).trim
				if (text.nonEmpty) // TODO: Check why texts can be empty
					boldWords = boldWords.enqueue(text)
			case _ =>
		}
		boldWords.map { word => Link(word, surfaceRepr(word), wikiPage.pageTitle, wikiPage.pageTitle) }
	}

	// Private helper function to do depth-first search in the node tree
	private def nodeIterator(startNode: WtNode)(nodeHandlerFunction: WtNode => Unit): Unit = {
		/**
		 * Stores information for the depth-first search of the document AST.
		 * @param node The node to handle.
		 * @param insideTemplate Whether or not the current node is a sub-node of a template.
		 *                       Important because template parameters need an recursive invocation
		 *                       of the parser, because they are not parsed by default.
		 */
		case class StackItem(node: WtNode, insideTemplate: Boolean)
		val nodeStack = mutable.Stack[StackItem](StackItem(startNode, false))

		/**
		 * Aggregate all text inside templates for performance reasons.
		 * This way, only one recursive Extractor instance is need, instead of one
		 * per template parameter.
		 * TODO: Think about whether this is better dealt with by a heuristic, e.g.
		 * only parse parameters with wiki markup or with a mininum length.
		 * The current situation somehow breaks the context, because template
		 * parameters are at the end.
		 */
		val aggregatedTemplateSource = new StringBuilder()
		while (nodeStack.nonEmpty) {
			val StackItem(node, insideTemplate) = nodeStack.pop()
			if (node != null) {
				nodeHandlerFunction(node)
				node match {
					case t: WtTemplate =>
						nodeStack.pushAll(node.iterator().toList.reverse.map(StackItem(_, true)))
					case txt: WtText if insideTemplate =>
						val source = txt.getContent.trim
						aggregatedTemplateSource.append(source)
						// To links between two overlapping template parameters, we introduce
						// a splitter, which must not be inside a link.
						aggregatedTemplateSource.append(s"\n$TEMPLATE_SEPARATOR\n")
					case _ =>
						nodeStack.pushAll(node.iterator().toList.reverse.map(StackItem(_, insideTemplate)))
				}
			}
		}

		// Now call the extractor recursively, to get the links inside templates.
		val sourceString = aggregatedTemplateSource.toString()
		if (sourceString.nonEmpty) {
			val templatePage = WikiPage.fromSource(wikiPage.pageTitle, sourceString)
			val newExtractor = new Extractor(templatePage, surfaceRepr)
			newExtractor.nodeIterator(newExtractor.compiledWikiPage)(nodeHandlerFunction)
		}
	}

	private def getCompiledWikiPage(wikiPage: WikiPage): EngPage = {
		val compiler = new WtEngineImpl(config)
		val pageTitle = PageTitle.make(config, wikiPage.pageTitle)
		val pageId = new PageId(pageTitle, 0)

		val page = compiler.postprocess(pageId, wikiPage.source, null).getPage

		page
	}

	val TEMPLATE_SEPARATOR = "#####"
	protected[wiki] def extractLinks(parentNode: WtNode): Seq[Link] = {
		links = Vector()
		nodeIterator(parentNode) { node =>
			extractPotentialLink(node)
		}
		links
	}

	private def extractPotentialLink(node: WtNode): Unit = {
		val link: Option[LinkWithNode] = Some(new LinkWithNode(node))
		link
			.flatMap(filterNonLinks)
			.flatMap(filterImages)
			.flatMap(filterFiles)
			.flatMap(filterCategories)
			.flatMap(filterTemplateSeparator)
			.flatMap(removeAnchorLinks)
			.flatMap(trimWhitespace)
			.flatMap(filterExternalLinks)
//			.flatMap(debugPrintAllLinks)
			.flatMap(toLink)
			.foreach { link =>
				links = links :+ link
			}
	}

	private def getText(link: WtContentNode): String = {
		link.iterator().flatMap {
			case textNode: WtText =>
				Some(textNode.getContent)
			case otherNode: WtContentNode =>
				Some(getText(otherNode))
			case _ => None
		}.mkString("")
	}

	private def filterTemplateSeparator(link: LinkWithNode): Option[LinkWithNode] = {
		if (link.text.contains(TEMPLATE_SEPARATOR))
			None
		else
			Some(link)
	}

	/**
	 * Filters out a wikiparser.Node, if it is not an internal link.
	 * @return Some(link), if it is a internal link, None otherwise.
	 */
	private def filterNonLinks(link: LinkWithNode): Option[LinkWithNode] = {
		if (!link.node.isInstanceOf[WtInternalLink])
			None
		else {
			val linkNode = link.node.asInstanceOf[WtInternalLink]
			link.destination = linkNode.getTarget.getAsString
			link.text = getText(linkNode.getTitle)
			Some(link)
		}
	}

	/**
	 * Prints all links at the current stage.
	 * This can be used for debugging.
	 * @return The unaltered link.
	 */
	private def debugPrintAllLinks(link: LinkWithNode): Option[LinkWithNode] = {
		println(link.text + "#" + link.destination)
		Some(link)
	}

	/**
	 * Filters out a link, if it starts with a given string, e.g. 'Image:' or
	 * 'Category'.
	 * @param startStrings The strings to check for.
	 * @return Some(link) if the link does not start with the given string,
	 *         None otherwise.
	 */
	private def filterStartsWith(link: LinkWithNode, startStrings: String*): Option[LinkWithNode] = {
		if (startStrings.exists { s =>
			link.destination.startsWith(s) ||
			link.destination.startsWith(s":$s") }) None
		else Some(link)
	}
	private def filterImages(link: LinkWithNode): Option[LinkWithNode] = filterStartsWith(link, "Image:")
	private def filterFiles(link: LinkWithNode): Option[LinkWithNode] = filterStartsWith(link, "File:")
	private def filterCategories(link: LinkWithNode): Option[LinkWithNode] = filterStartsWith(link, "Category:")

	/**
	 * Handles anchor links like Germany#History (link to a specific point in
	 * a page) and removes the part after '#'
	 * @return The sanitized link.
	 */
	private def removeAnchorLinks(link: LinkWithNode): Option[LinkWithNode] = {
		if (link.text.trim == "")
			link.text = link.destination
		val hashTagIndex = link.destination.indexOf("#")
		// if a hashtag was found, but not on the first position
		if (hashTagIndex != -1 && hashTagIndex != 0)
			link.destination = link.destination.substring(0, hashTagIndex)
		Some(link)
	}

	/**
	 * Handles link texts like '  Germany ' and removes the whitespace.
	 * @return The sanitized link.
	 */
	private def trimWhitespace(link: LinkWithNode): Option[LinkWithNode] = {
		link.text = link.text.trim
		while (link.text.startsWith("\"") && link.text.endsWith("\"") && link.text.size > 1)
			link.text = link.text.drop(1).dropRight(1)
		link.text = link.text.replace("\r\n", " ").replace('\n', ' ')
		Some(link)
	}

	/**
	 * Filters external links that are not recognized by the parser, because the markup
	 * had some errors, e.g. if the user used double brackets for external links like
	 * [[http://www.google.de]].
	 * @return None, if it is an external link, Some(link) otherwise.
	 */
	private def filterExternalLinks(link: LinkWithNode): Option[LinkWithNode] = {
		if (link.destination.toLowerCase.startsWith("http://"))
			None
		else Some(link)
	}

	/**
	 * Translates an internal link to an link, that can be exposed to the user.
	 */
	private def toLink(link: LinkWithNode): Option[Link] = {
		Some(Link(link.text, surfaceRepr(link.text), wikiPage.pageTitle, link.destination))
	}
}
