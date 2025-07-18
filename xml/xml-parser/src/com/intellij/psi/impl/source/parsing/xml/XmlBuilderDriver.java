// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

/*
 * @author max
 */
package com.intellij.psi.impl.source.parsing.xml;

import com.intellij.lang.*;
import com.intellij.lang.impl.PsiBuilderImpl;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.xml.IXmlAttributeElementType;
import com.intellij.psi.xml.IXmlTagElementType;
import com.intellij.psi.xml.XmlElementType;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.util.containers.Stack;
import com.intellij.util.diff.FlyweightCapableTreeStructure;
import com.intellij.xml.util.BasicXmlUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class XmlBuilderDriver {
  private final Stack<@NotNull String> myNamespacesStack = new Stack<>();
  private final Stack<@NotNull String> myPrefixesStack = new Stack<>();
  private final CharSequence myText;
  private static final @NonNls String XMLNS = "xmlns";
  private static final @NonNls String XMLNS_COLON = "xmlns:";

  public XmlBuilderDriver(@NotNull CharSequence text) {
    myText = text;
  }

  protected @NotNull CharSequence getText() {
    return myText;
  }

  public void addImplicitBinding(@NonNls @NotNull String prefix,
                                 @NonNls @NotNull String namespace) {
    myNamespacesStack.push(namespace);
    myPrefixesStack.push(prefix);
  }

  public void build(@NotNull XmlBuilder builder) {
    PsiBuilder b = createBuilderAndParse();

    FlyweightCapableTreeStructure<LighterASTNode> structure = b.getLightTree();

    LighterASTNode root = structure.getRoot();

    final Ref<LighterASTNode[]> childrenRef = Ref.create(null);
    final int count = structure.getChildren(root, childrenRef);
    LighterASTNode[] children = childrenRef.get();

    for (int i = 0; i < count; i++) {
      LighterASTNode child = children[i];
      final IElementType tt = child.getTokenType();
      if (tt instanceof IXmlTagElementType) {
        processTagNode(structure, child, builder);
      }
      else if (tt == XmlElementType.XML_PROLOG) {
        processPrologNode(builder, structure, child);
      }
    }

    structure.disposeChildren(children, count);
  }

  private void processPrologNode(@NotNull XmlBuilder builder,
                                 @NotNull FlyweightCapableTreeStructure<LighterASTNode> structure,
                                 @NotNull LighterASTNode prolog) {
    final Ref<LighterASTNode[]> prologChildren = new Ref<>(null);
    final int prologChildrenCount = structure.getChildren(prolog, prologChildren);
    for (int i = 0; i < prologChildrenCount; i++) {
      LighterASTNode node = prologChildren.get()[i];
      IElementType type = node.getTokenType();
      if (type == XmlElementType.XML_DOCTYPE) {
        processDoctypeNode(builder, structure, node);
        break;
      }
      if (type == TokenType.ERROR_ELEMENT) {
        processErrorNode(node, builder);
      }
    }
  }

  private void processDoctypeNode(@NotNull XmlBuilder builder,
                                  @NotNull FlyweightCapableTreeStructure<LighterASTNode> structure,
                                  @NotNull LighterASTNode doctype) {
    final Ref<LighterASTNode[]> tokens = new Ref<>(null);
    final int tokenCount = structure.getChildren(doctype, tokens);
    if (tokenCount > 0) {
      CharSequence publicId = null;
      boolean afterPublic = false;
      CharSequence systemId = null;
      boolean afterSystem = false;
      for (int i = 0; i < tokenCount; i++) {
        LighterASTNode token = tokens.get()[i];
        if (token.getTokenType() == XmlTokenType.XML_DOCTYPE_PUBLIC) {
          afterPublic = true;
        }
        else if (token.getTokenType() == XmlTokenType.XML_DOCTYPE_SYSTEM) {
          afterSystem = true;
        }
        else if (token.getTokenType() != TokenType.WHITE_SPACE && token.getTokenType() != XmlElementType.XML_COMMENT) {
          if (token.getTokenType() == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN) {
            if (afterPublic) {
              publicId = getTokenText(token);
            }
            else if (afterSystem) systemId = getTokenText(token);
          }
          afterPublic = afterSystem = false;
        }
      }
      builder.doctype(publicId, systemId, doctype.getStartOffset(), doctype.getEndOffset());
    }
  }

  private @NotNull CharSequence getTokenText(@NotNull LighterASTNode token) {
    return myText.subSequence(token.getStartOffset(), token.getEndOffset());
  }

  protected @NotNull PsiBuilder createBuilderAndParse() {
    final ParserDefinition xmlParserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(XMLLanguage.INSTANCE);
    assert xmlParserDefinition != null;

    PsiBuilder b = PsiBuilderFactory.getInstance().createBuilder(xmlParserDefinition, xmlParserDefinition.createLexer(null), myText);
    new XmlParsing(b).parseDocument();
    return b;
  }

  private static void processErrorNode(@NotNull LighterASTNode node,
                                       @NotNull XmlBuilder builder) {
    assert node.getTokenType() == TokenType.ERROR_ELEMENT;
    String message = PsiBuilderImpl.getErrorMessage(node);
    assert message != null;
    builder.error(message, node.getStartOffset(), node.getEndOffset());
  }

  private void processTagNode(@NotNull FlyweightCapableTreeStructure<LighterASTNode> structure,
                              @NotNull LighterASTNode node,
                              @NotNull XmlBuilder builder) {
    final IElementType nodeTT = node.getTokenType();
    assert nodeTT instanceof IXmlTagElementType;

    final Ref<LighterASTNode[]> childrenRef = Ref.create(null);
    final int count = structure.getChildren(node, childrenRef);
    LighterASTNode[] children = childrenRef.get();

    int stackFrameSize = myNamespacesStack.size();
    CharSequence tagName = "";
    int headerEndOffset = node.getEndOffset();
    for (int i = 0; i < count; i++) {
      LighterASTNode child = children[i];
      final IElementType tt = child.getTokenType();
      if (tt instanceof IXmlAttributeElementType) checkForXmlns(child, structure);
      if (tt == XmlTokenType.XML_TAG_END || tt == XmlTokenType.XML_EMPTY_ELEMENT_END) {
        headerEndOffset = child.getEndOffset();
        break;
      }
      if (tt == XmlTokenType.XML_NAME || tt == XmlTokenType.XML_TAG_NAME) {
        tagName = getTokenText(child);
      }
    }

    CharSequence localName = BasicXmlUtil.getLocalName(tagName);
    String namespace = getNamespace(tagName);

    XmlBuilder.ProcessingOrder order = builder.startTag(localName, namespace, node.getStartOffset(), node.getEndOffset(), headerEndOffset);
    boolean processAttrs = order == XmlBuilder.ProcessingOrder.TAGS_AND_ATTRIBUTES ||
                           order == XmlBuilder.ProcessingOrder.TAGS_AND_ATTRIBUTES_AND_TEXTS;

    boolean processTexts = order == XmlBuilder.ProcessingOrder.TAGS_AND_TEXTS ||
                           order == XmlBuilder.ProcessingOrder.TAGS_AND_ATTRIBUTES_AND_TEXTS;

    for (int i = 0; i < count; i++) {
      LighterASTNode child = children[i];
      IElementType tt = child.getTokenType();
      if (tt == TokenType.ERROR_ELEMENT) processErrorNode(child, builder);
      if (tt instanceof IXmlTagElementType) processTagNode(structure, child, builder);
      if (processAttrs && tt instanceof IXmlAttributeElementType) processAttributeNode(child, structure, builder);
      if (processTexts && tt == XmlElementType.XML_TEXT) processTextNode(structure, child, builder);
      if (tt == XmlElementType.XML_ENTITY_REF) builder.entityRef(getTokenText(child), child.getStartOffset(), child.getEndOffset());
    }

    builder.endTag(localName, namespace, node.getStartOffset(), node.getEndOffset());

    int framesToDrop = myNamespacesStack.size() - stackFrameSize;
    for (int i = 0; i < framesToDrop; i++) {
      myNamespacesStack.pop();
      myPrefixesStack.pop();
    }

    structure.disposeChildren(children, count);
  }

  private void processTextNode(@NotNull FlyweightCapableTreeStructure<LighterASTNode> structure,
                               @NotNull LighterASTNode node,
                               @NotNull XmlBuilder builder) {
    final Ref<LighterASTNode[]> childrenRef = Ref.create(null);
    final int count = structure.getChildren(node, childrenRef);
    LighterASTNode[] children = childrenRef.get();

    for (int i = 0; i < count; i++) {
      LighterASTNode child = children[i];
      IElementType tt = child.getTokenType();
      final int start = child.getStartOffset();
      final int end = child.getEndOffset();
      final CharSequence physical = getTokenText(child);

      if (XmlTokenType.COMMENTS.contains(tt)) continue;

      if (tt == XmlTokenType.XML_CDATA_START || tt == XmlTokenType.XML_CDATA_END) {
        builder.textElement("", physical, start, end);
      }
      else if (tt == XmlElementType.XML_CDATA) {
        processTextNode(structure, child, builder);
      }
      else if (tt == XmlTokenType.XML_CHAR_ENTITY_REF) {
        builder.textElement(new String(new char[]{BasicXmlUtil.getCharFromEntityRef(physical.toString())}), physical, start, end);
      }
      else {
        builder.textElement(physical, physical, start, end);
      }
    }

    structure.disposeChildren(children, count);
  }

  private void processAttributeNode(@NotNull LighterASTNode attrNode,
                                    @NotNull FlyweightCapableTreeStructure<LighterASTNode> structure,
                                    @NotNull XmlBuilder builder) {
    builder.attribute(
      getAttributeName(attrNode, structure),
      getAttributeValue(attrNode, structure),
      attrNode.getStartOffset(),
      attrNode.getEndOffset()
    );
  }

  private @NonNls @NotNull String getNamespace(@NotNull CharSequence tagName) {
    final String namespacePrefix;
    int pos = StringUtil.indexOf(tagName, ':');
    if (pos == -1) {
      namespacePrefix = "";
    }
    else {
      namespacePrefix = tagName.subSequence(0, pos).toString();
    }

    for (int i = myPrefixesStack.size() - 1; i >= 0; i--) {
      if (namespacePrefix.equals(myPrefixesStack.get(i))) return myNamespacesStack.get(i);
    }

    return "";
  }

  private void checkForXmlns(@NotNull LighterASTNode attrNode,
                             @NotNull FlyweightCapableTreeStructure<LighterASTNode> structure) {
    final CharSequence name = getAttributeName(attrNode, structure);
    if (Comparing.equal(name, XMLNS)) {
      myPrefixesStack.push("");
      myNamespacesStack.push(getAttributeValue(attrNode, structure).toString());
    }
    else if (StringUtil.startsWith(name, XMLNS_COLON)) {
      myPrefixesStack.push(name.subSequence(XMLNS_COLON.length(), name.length()).toString());
      myNamespacesStack.push(getAttributeValue(attrNode, structure).toString());
    }
  }


  private @NotNull CharSequence getAttributeName(@NotNull LighterASTNode attrNode,
                                                 @NotNull FlyweightCapableTreeStructure<LighterASTNode> structure) {
    return findTextByTokenType(attrNode, structure, XmlTokenType.XML_NAME);
  }

  private @NotNull CharSequence getAttributeValue(@NotNull LighterASTNode attrNode,
                                                  @NotNull FlyweightCapableTreeStructure<LighterASTNode> structure) {
    final CharSequence fullValue = findTextByTokenType(attrNode, structure, XmlElementType.XML_ATTRIBUTE_VALUE);
    int start = 0;
    if (!fullValue.isEmpty() && fullValue.charAt(0) == '\"') start++;

    int end = fullValue.length();
    if (fullValue.length() > start && fullValue.charAt(fullValue.length() - 1) == '\"') end--;

    return fullValue.subSequence(start, end);
  }

  private @NotNull CharSequence findTextByTokenType(@NotNull LighterASTNode attrNode,
                                                    @NotNull FlyweightCapableTreeStructure<LighterASTNode> structure,
                                                    @NotNull IElementType tt) {
    final Ref<LighterASTNode[]> childrenRef = Ref.create(null);
    final int count = structure.getChildren(attrNode, childrenRef);
    LighterASTNode[] children = childrenRef.get();

    CharSequence name = "";
    for (int i = 0; i < count; i++) {
      LighterASTNode child = children[i];
      if (child.getTokenType() == tt) {
        name = getTokenText(child);
        break;
      }
    }

    structure.disposeChildren(children, count);

    return name;
  }
}
