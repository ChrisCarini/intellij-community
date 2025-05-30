// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.java.decompiler.struct;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AnnotationExprent;
import org.jetbrains.java.decompiler.modules.decompiler.typeann.TargetInfo;
import org.jetbrains.java.decompiler.modules.decompiler.typeann.TypeAnnotation;
import org.jetbrains.java.decompiler.struct.attr.*;
import org.jetbrains.java.decompiler.struct.consts.ConstantPool;
import org.jetbrains.java.decompiler.struct.gen.Type;
import org.jetbrains.java.decompiler.util.DataInputFullStream;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute.ATTRIBUTE_LOCAL_VARIABLE_TABLE;
import static org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute.ATTRIBUTE_LOCAL_VARIABLE_TYPE_TABLE;

public abstract class StructMember {
  protected int accessFlags;
  protected Map<String, StructGeneralAttribute> attributes;

  protected StructMember(int accessFlags, Map<String, StructGeneralAttribute> attributes) {
    this.accessFlags = accessFlags;
    this.attributes = attributes;
  }

  public int getAccessFlags() {
    return accessFlags;
  }

  public <T extends StructGeneralAttribute> T getAttribute(StructGeneralAttribute.Key<T> attribute) {
    @SuppressWarnings("unchecked") T t = (T)attributes.get(attribute.name);
    return t;
  }

  public boolean hasAttribute(StructGeneralAttribute.Key<?> attribute) {
    return attributes.containsKey(attribute.name);
  }

  public boolean hasModifier(int modifier) {
    boolean result = (accessFlags & modifier) == modifier;
    if (!result && modifier == CodeConstants.ACC_STATIC &&
        this instanceof StructClass struct &&
        struct.hasAttribute(StructGeneralAttribute.ATTRIBUTE_INNER_CLASSES)) {
      StructInnerClassesAttribute attr = struct.getAttribute(StructGeneralAttribute.ATTRIBUTE_INNER_CLASSES);
      for (StructInnerClassesAttribute.Entry entry : attr.getEntries()) {
        if (entry.innerName != null && entry.innerName.equals(struct.qualifiedName)) {
          return (entry.accessFlags & CodeConstants.ACC_STATIC) == CodeConstants.ACC_STATIC;
        }
      }
    }
    return result;
  }

  public boolean isSynthetic() {
    return hasModifier(CodeConstants.ACC_SYNTHETIC) || hasAttribute(StructGeneralAttribute.ATTRIBUTE_SYNTHETIC);
  }

  protected abstract Type getType();

  /**
   * Checks whether annotations should go on type instead of member
   */
  public boolean memberAnnCollidesWithTypeAnnotation(AnnotationExprent typeAnnotationExpr) {
    Type type = getType();
    if (type == null) return false; // when there is no type reference no collision is possible
    Set<AnnotationExprent> typeAnnotations = TargetInfo.EmptyTarget.extract(getPossibleTypeAnnotationCollisions())
      .stream()
      .map(typeAnnotation-> typeAnnotation.getAnnotationExpr())
      .collect(Collectors.toUnmodifiableSet());
    return typeAnnotations.contains(typeAnnotationExpr);
  }

  /**
   * Checks whether annotations should go on parameter type instead of parameter
   */
  public boolean paramAnnCollidesWithTypeAnnotation(AnnotationExprent typeAnnotationExpr, int param) {
    Set<AnnotationExprent> typeAnnotations = TargetInfo.FormalParameterTarget
      .extract(getPossibleTypeAnnotationCollisions(), param).stream()
      .map(typeAnnotation-> typeAnnotation.getAnnotationExpr())
      .collect(Collectors.toUnmodifiableSet());
    return typeAnnotations.contains(typeAnnotationExpr);
  }

  private List<TypeAnnotation> getPossibleTypeAnnotationCollisions() {
    return Arrays.stream(StructGeneralAttribute.TYPE_ANNOTATION_ATTRIBUTES)
      .flatMap(attrKey -> {
        StructTypeAnnotationAttribute attribute = (StructTypeAnnotationAttribute)getAttribute(attrKey);
        if (attribute == null) {
          return Stream.empty();
        } else {
          return attribute.getAnnotations().stream();
        }
      })
      .collect(Collectors.toList());
  }

  public static Map<String, StructGeneralAttribute> readAttributes(DataInputFullStream in, ConstantPool pool) throws IOException {
    int length = in.readUnsignedShort();
    Map<String, StructGeneralAttribute> attributes = new HashMap<>(length);

    for (int i = 0; i < length; i++) {
      int nameIndex = in.readUnsignedShort();
      String name = pool.getPrimitiveConstant(nameIndex).getString();

      StructGeneralAttribute attribute = StructGeneralAttribute.createAttribute(name);
      int attLength = in.readInt();
      if (attribute == null) {
        in.discard(attLength);
      }
      else {
        attribute.initContent(in, pool);
        if (StructGeneralAttribute.ATTRIBUTE_LOCAL_VARIABLE_TABLE.name.equals(name) && attributes.containsKey(name)) {
          // merge all variable tables
          StructLocalVariableTableAttribute table = (StructLocalVariableTableAttribute)attributes.get(name);
          table.add((StructLocalVariableTableAttribute)attribute);
        }
        else if (StructGeneralAttribute.ATTRIBUTE_LOCAL_VARIABLE_TYPE_TABLE.name.equals(name) && attributes.containsKey(name)) {
          // merge all variable tables
          StructLocalVariableTypeTableAttribute table = (StructLocalVariableTypeTableAttribute)attributes.get(name);
          table.add((StructLocalVariableTypeTableAttribute)attribute);
        }
        else {
          attributes.put(name, attribute);
        }
      }
    }

    if (attributes.containsKey(ATTRIBUTE_LOCAL_VARIABLE_TABLE.name) && attributes.containsKey(ATTRIBUTE_LOCAL_VARIABLE_TYPE_TABLE.name))
      ((StructLocalVariableTableAttribute)attributes.get(ATTRIBUTE_LOCAL_VARIABLE_TABLE.name)).mergeSignatures((StructLocalVariableTypeTableAttribute)attributes.get(ATTRIBUTE_LOCAL_VARIABLE_TYPE_TABLE.name));
    return attributes;
  }
}
