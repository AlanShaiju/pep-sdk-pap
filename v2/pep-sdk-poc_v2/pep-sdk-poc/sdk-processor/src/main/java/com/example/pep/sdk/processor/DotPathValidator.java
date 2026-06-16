package com.example.pep.sdk.processor;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Validates @PapInclude.attribute dot-paths at compile time: each segment must be a
 * declared field on the corresponding type (walking superclasses).
 */
final class DotPathValidator {

    private final Types types;

    DotPathValidator(Types types) {
        this.types = types;
    }

    /**
     * @param startType the relationship field's declared type
     * @param dotPath   e.g. "region.code"
     * @return null if the path resolves; otherwise a human-readable error message
     */
    String validate(TypeMirror startType, String dotPath) {
        TypeMirror current = startType;
        for (String segment : dotPath.split("\\.")) {
            TypeElement typeElement = asTypeElement(current);
            if (typeElement == null) {
                return "segment '" + segment + "': " + current + " is not a class type";
            }
            VariableElement field = findField(typeElement, segment);
            if (field == null) {
                return "type " + typeElement.getSimpleName() + " has no field '" + segment + "'";
            }
            current = field.asType();
        }
        return null;
    }

    private TypeElement asTypeElement(TypeMirror mirror) {
        if (!(mirror instanceof DeclaredType declared)) return null;
        Element e = declared.asElement();
        return e instanceof TypeElement te ? te : null;
    }

    /** Find a field by name on the type or any superclass. */
    private VariableElement findField(TypeElement type, String name) {
        TypeElement current = type;
        while (current != null) {
            for (Element enc : current.getEnclosedElements()) {
                if (enc instanceof VariableElement field
                        && field.getSimpleName().contentEquals(name)) {
                    return field;
                }
            }
            TypeMirror superMirror = current.getSuperclass();
            current = asTypeElement(superMirror);
        }
        return null;
    }
}
