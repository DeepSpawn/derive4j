/*
 * Copyright (c) 2015, Jean-Baptiste Giraudeau <jb@giraudeau.info>
 *
 * This file is part of "Derive4J - Annotation Processor".
 *
 * "Derive4J - Annotation Processor" is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * "Derive4J - Annotation Processor" is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with "Derive4J - Annotation Processor".  If not, see <http://www.gnu.org/licenses/>.
 */
package org.derive4j.processor.derivator;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.derive4j.processor.Utils;
import org.derive4j.processor.api.DeriveResult;
import org.derive4j.processor.api.DeriveUtils;
import org.derive4j.processor.api.DerivedCodeSpec;
import org.derive4j.processor.api.model.AlgebraicDataType;
import org.derive4j.processor.api.model.DeriveContext;
import org.derive4j.processor.api.model.TypeConstructor;

import static org.derive4j.processor.Utils.optionalAsStream;
import static org.derive4j.processor.api.DeriveResult.result;
import static org.derive4j.processor.api.DerivedCodeSpec.codeSpec;
import static org.derive4j.processor.api.DerivedCodeSpec.none;
import static org.derive4j.processor.derivator.StrictConstructorDerivator.findAbstractEquals;
import static org.derive4j.processor.derivator.StrictConstructorDerivator.findAbstractHashCode;
import static org.derive4j.processor.derivator.StrictConstructorDerivator.findAbstractToString;

public final class LazyConstructorDerivator {

  public static DeriveResult<DerivedCodeSpec> derive(AlgebraicDataType adt, DeriveContext deriveContext, DeriveUtils deriveUtils) {

    // skip constructors for enums
    if (adt.typeConstructor().declaredType().asElement().getKind() == ElementKind.ENUM) {
      return result(none());
    }

    TypeConstructor typeConstructor = adt.typeConstructor();
    TypeElement lazyTypeElement = FlavourImpl.findF0(deriveContext.flavour(), deriveUtils.elements());
    TypeName lazyArgTypeName = TypeName.get(deriveUtils.types().getDeclaredType(lazyTypeElement, typeConstructor.declaredType()));
    String lazyArgName = Utils.uncapitalize(typeConstructor.typeElement().getSimpleName());
    TypeName typeName = TypeName.get(typeConstructor.declaredType());

    List<TypeVariableName> typeVariableNames = adt.typeConstructor().typeVariables().stream().map(TypeVariableName::get).collect(Collectors.toList());

    String className = "Lazy";
    TypeSpec.Builder typeSpecBuilder = TypeSpec.classBuilder(className)
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .addTypeVariables(typeVariableNames)
        .addField(FieldSpec.builder(TypeName.get(Object.class), "lock", Modifier.PRIVATE, Modifier.FINAL).initializer("new Object()").build())
        .addField(FieldSpec.builder(lazyArgTypeName, "expression", Modifier.PRIVATE).build())
        .addField(FieldSpec.builder(typeName, "evaluation", Modifier.PRIVATE, Modifier.VOLATILE).build())
        .addMethod(MethodSpec.constructorBuilder()
            .addParameter(ParameterSpec.builder(lazyArgTypeName, lazyArgName).build())
            .addStatement("this.expression = $N", lazyArgName)
            .build())
        .addMethod(MethodSpec.methodBuilder("eval")
            .addModifiers(Modifier.PRIVATE)
            .returns(typeName)
            .addCode(CodeBlock.builder()
                .addStatement("$T _evaluation = this.evaluation", typeName)
                .beginControlFlow("if (_evaluation == null)")
                .beginControlFlow("synchronized (this.lock)")
                .addStatement("_evaluation = this.evaluation")
                .beginControlFlow("if (_evaluation == null)")
                .addStatement("this.evaluation = _evaluation = expression.$L()",
                    deriveUtils.allAbstractMethods(lazyTypeElement).get(0).getSimpleName())
                .addStatement("this.expression = null")
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                .addStatement("return _evaluation")
                .build())
            .build())
        .addMethod(Utils.overrideMethodBuilder(adt.matchMethod().element())
            .addStatement("return eval().$L($L)", adt.matchMethod().element().getSimpleName(),
                Utils.asArgumentsStringOld(adt.matchMethod().element().getParameters()))
            .build());

    if (adt.typeConstructor().declaredType().asElement().getKind() == ElementKind.INTERFACE) {
      typeSpecBuilder.addSuperinterface(typeName);
    } else {
      typeSpecBuilder.superclass(typeName);
    }

    typeSpecBuilder.addMethods(optionalAsStream(findAbstractEquals(deriveUtils, typeConstructor.typeElement()).map(
        equals -> deriveUtils.overrideMethodBuilder(equals, adt.typeConstructor().declaredType())
            .addStatement("return this.eval().equals($L)", equals.getParameters().get(0).getSimpleName())
            .build())).collect(Collectors.toList()));

    typeSpecBuilder.addMethods(optionalAsStream(findAbstractHashCode(deriveUtils, typeConstructor.typeElement()).map(
        hashCode -> deriveUtils.overrideMethodBuilder(hashCode, adt.typeConstructor().declaredType())
            .addStatement("return this.eval().hashCode()")
            .build())).collect(Collectors.toList()));

    typeSpecBuilder.addMethods(optionalAsStream(findAbstractToString(deriveUtils, typeConstructor.typeElement()).map(
        toString -> deriveUtils.overrideMethodBuilder(toString, adt.typeConstructor().declaredType())
            .addStatement("return this.eval().toString()")
            .build())).collect(Collectors.toList()));

    return result(codeSpec(typeSpecBuilder.build(), MethodSpec.methodBuilder("lazy")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addTypeVariables(typeConstructor.typeVariables().stream().map(TypeVariableName::get).collect(Collectors.toList()))
        .addParameter(lazyArgTypeName, lazyArgName)
        .returns(typeName)
        .addStatement("return new $L$L($L)", className, typeVariableNames.isEmpty()
                                                        ? ""
                                                        : "<>", lazyArgName)
        .build()));

  }

}
