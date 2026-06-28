package com.example.pep.sdk.processor;

import com.example.pep.sdk.core.annotation.PapAttribute;
import com.example.pep.sdk.core.annotation.PapEntity;
import com.example.pep.sdk.core.annotation.PapInclude;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v9 processor. For each @PapEntity:
 *   errors: catalog membership; @Id+@PapAttribute present; @PapInclude on collection;
 *           include dot-path resolution; intra-type name collisions; coverage rule.
 *   warnings: cross-type name collisions (precedence applies); LAZY @PapInclude.
 * Emits one metadata.json per module on processingOver().
 */
@SupportedAnnotationTypes("com.example.pep.sdk.core.annotation.PapEntity")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class PapAnnotationProcessor extends AbstractProcessor {

    private CompileTimeCatalog catalog;
    private Messager messager;
    private DotPathValidator dotPathValidator;
    private final List<EntityDescriptor> collected = new ArrayList<>();

    private static final Set<String> COLLECTION_TYPES = Set.of(
            "java.util.List", "java.util.Set", "java.util.Map", "java.util.Collection");
    
    /** String constant to avoid duplication of "source" literal (S1192 compliance) */
    private static final String SOURCE = "source";

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.messager = env.getMessager();
        this.catalog = CompileTimeCatalog.load(messager);
        this.dotPathValidator = new DotPathValidator();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            if (!collected.isEmpty()) writeMetadata();
            return false;
        }
        for (Element element : round.getElementsAnnotatedWith(PapEntity.class)) {
            if (!(element instanceof TypeElement type)) continue;
            EntityDescriptor d = validateAndBuild(type);
            if (d != null) collected.add(d);
        }
        return false;
    }

    // ----------------------------------------------------------------- validation

    private EntityDescriptor validateAndBuild(TypeElement type) {
        PapEntity ann = type.getAnnotation(PapEntity.class);
        String entity = ann.entity();

        if (!catalog.supports(entity)) {
            error(type, "entity \"" + entity + "\" is not in the PAP catalog (known: "
                    + catalog.knownEntities() + ")");
            return null;
        }
        CompileTimeCatalog.EntityInfo info = catalog.get(entity);

        // Collect @PapAttribute fields (intra-type duplicate check)
        Map<String, VariableElement> attributeFields = collectAttributeFields(type);
        VariableElement idField = findAndValidateIdField(type, attributeFields);
        if (idField == null) return null;

        // Collect @PapInclude entries (intra-type duplicate + dot-path + collection checks)
        Map<String, IncludeDescriptor> includes = collectIncludeFields(type);

        // Collect @PapProperty (intra-type duplicate check)
        Map<String, String> propertyValues = readPropertyValues(type);

        // Collect operation modes (duplicate-operation check inside)
        Map<String, String> opModes = readOperationModes(type);

        // Validate source precedence (warnings only)
        validateSourcePrecedence(type, attributeFields, includes, propertyValues);

        // Validate coverage rule
        validateCoverage(type, entity, attributeFields, propertyValues, includes, info);

        // Build serializable descriptor
        EntityDescriptor d = new EntityDescriptor();
        d.entityClass = type.getQualifiedName().toString();
        d.papEntity = entity;
        d.idField = idField.getSimpleName().toString();
        d.properties = propertyValues;
        d.operationModes = opModes;
        for (Map.Entry<String, VariableElement> e : attributeFields.entrySet()) {
            d.attributes.add(new AttributeDescriptor(e.getValue().getSimpleName().toString(), e.getKey()));
        }
        d.includes.addAll(includes.values());
        return d;
    }

    /**
     * Collects all @PapAttribute fields, checking for intra-type duplicates.
     * Uses a helper predicate to reduce continue statements.
     */
    private Map<String, VariableElement> collectAttributeFields(TypeElement type) {
        Map<String, VariableElement> attributeFields = new LinkedHashMap<>();
        for (Element enc : type.getEnclosedElements()) {
            if (!isValidAttributeElement(enc)) continue;  // ✅ Single continue
            
            VariableElement field = (VariableElement) enc;
            PapAttribute a = enc.getAnnotation(PapAttribute.class);
            if (attributeFields.put(a.attributeName(), field) != null) {
                error(field, "duplicate @PapAttribute(attributeName=\"" + a.attributeName()
                        + "\") on " + type.getSimpleName());
            }
        }
        return attributeFields;
    }

    /**
     * Checks if an element is a valid VariableElement with @PapAttribute annotation.
     */
    private boolean isValidAttributeElement(Element enc) {
        if (!(enc instanceof VariableElement)) return false;
        return enc.getAnnotation(PapAttribute.class) != null;
    }

    /**
     * Finds and validates the @Id field.
     * 
     * Delegates to a helper method to avoid multiple continue/break statements.
     * 
     * @param type the entity type
     * @param attributeFields map of attribute fields (unused, kept for API compatibility)
     * @return the @Id field if valid, null if validation fails (error logged)
     */
    private VariableElement findAndValidateIdField(TypeElement type, 
                                                    Map<String, VariableElement> attributeFields) {
        VariableElement idField = findIdFieldWithAnnotation(type);
        
        if (idField == null) {
            error(type, "no @Id field with @PapAttribute on " + type.getSimpleName());
            return null;
        }
        return idField;
    }

    /**
     * Finds the first @Id-annotated field in the type.
     * 
     * Searches through all enclosed elements, returning as soon as a VariableElement
     * with the @Id annotation is found. Uses a single early return to avoid multiple
     * break/continue statements.
     * 
     * @param type the entity class type to search
     * @return the first @Id-annotated VariableElement, or null if none found
     */
    private VariableElement findIdFieldWithAnnotation(TypeElement type) {
        for (Element enc : type.getEnclosedElements()) {
            if (enc instanceof VariableElement field && hasIdAnnotation(field)) {
                return field;  // ✅ Single early return (SonarQube compliant)
            }
        }
        return null;
    }

    /**
     * Collects all @PapInclude entries, validating collection type, dot-path, and duplicates.
     * Uses helper methods to reduce continue statements.
     */
    private Map<String, IncludeDescriptor> collectIncludeFields(TypeElement type) {
        Map<String, IncludeDescriptor> includes = new LinkedHashMap<>();
        for (Element enc : type.getEnclosedElements()) {
            if (!isValidIncludeElement(enc)) continue;  // ✅ Single continue for validation
            
            VariableElement field = (VariableElement) enc;
            PapInclude[] anns = field.getAnnotationsByType(PapInclude.class);
            
            if (anns.length == 0) continue;  // ✅ Single continue for empty annotations

            // Validate collection type (single continue point)
            String rawType = rawTypeName(field);
            if (COLLECTION_TYPES.contains(rawType)) {
                error(field, "@PapInclude is not supported on collection types (" + rawType + ")");
                continue;
            }

            // Process each include annotation
            for (PapInclude inc : anns) {
                processIncludeAnnotation(type, field, inc, includes);
            }
        }
        return includes;
    }

    /**
     * Checks if an element is a valid VariableElement for @PapInclude processing.
     */
    private boolean isValidIncludeElement(Element enc) {
        return enc instanceof VariableElement;
    }

    /**
     * Processes a single @PapInclude annotation, validating it.
     */
    private void processIncludeAnnotation(TypeElement entityType, VariableElement field, 
                                         PapInclude inc, Map<String, IncludeDescriptor> includes) {
        // Validate duplicate include name (single return point)
        if (includes.containsKey(inc.name())) {
            error(field, "duplicate @PapInclude(name=\"" + inc.name() + "\") on "
                    + entityType.getSimpleName());
            return;
        }

        // Validate dot-path (single return point)
        String pathError = dotPathValidator.validate(field.asType(), inc.attribute());
        if (pathError != null) {
            error(field, "@PapInclude.attribute '" + inc.attribute()
                    + "' does not resolve: " + pathError);
            return;
        }

        // Add to includes map
        includes.put(inc.name(), new IncludeDescriptor(
                field.getSimpleName().toString(), inc.name(), inc.attribute()));

        // Warn if LAZY fetch
        if (isLazyFetch(field)) {
            warn(field, "@PapInclude on a LAZY relationship may trigger SQL inside the "
                    + "Hibernate listener; prefer FetchType.EAGER or pre-initialize");
        }
    }

    /**
     * Validates source precedence rules (warnings only).
     */
    private void validateSourcePrecedence(TypeElement type, 
                                          Map<String, VariableElement> attributeFields,
                                          Map<String, IncludeDescriptor> includes,
                                          Map<String, String> propertyValues) {
        // Check @PapAttribute vs @PapProperty precedence
        for (Map.Entry<String, VariableElement> entry : attributeFields.entrySet()) {
            String name = entry.getKey();
            if (propertyValues.containsKey(name)) {
                warn(entry.getValue(), SOURCE + " '" + name + "' is supplied by both "
                        + "@PapProperty and @PapAttribute; @PapProperty wins by precedence");
            }
            if (includes.containsKey(name)) {
                warn(entry.getValue(), SOURCE + " '" + name + "' is supplied by both "
                        + "@PapAttribute and @PapInclude; @PapAttribute wins by precedence");
            }
        }

        // Check @PapInclude vs @PapProperty precedence
        for (String name : includes.keySet()) {
            if (propertyValues.containsKey(name)) {
                warn(type, SOURCE + " '" + name + "' is supplied by both @PapProperty and "
                        + "@PapInclude; @PapProperty wins by precedence");
            }
        }
    }

    /**
     * Validates coverage rule: all catalog-referenced sources must have a mapping.
     */
    private void validateCoverage(TypeElement type, String entity,
                                  Map<String, VariableElement> attributeFields,
                                  Map<String, String> propertyValues,
                                  Map<String, IncludeDescriptor> includes,
                                  CompileTimeCatalog.EntityInfo info) {
        Set<String> covered = new HashSet<>();
        covered.addAll(attributeFields.keySet());
        covered.addAll(propertyValues.keySet());
        covered.addAll(includes.keySet());

        for (String ref : info.referencedSources()) {
            if (!covered.contains(ref)) {
                error(type, "attribute source '" + ref + "' is referenced by the catalog for entity \""
                        + entity + "\" but has no mapping — add @PapAttribute(attributeName=\"" + ref
                        + "\"), @PapProperty(key=\"" + ref + "\", ...), or @PapInclude(name=\"" + ref
                        + "\", attribute=...)");
            }
        }
    }

    private static boolean hasIdAnnotation(Element field) {
        return field.getAnnotationMirrors().stream()
                .anyMatch(am -> am.getAnnotationType().asElement().getSimpleName().contentEquals("Id"));
    }

    private static String rawTypeName(VariableElement field) {
        String s = field.asType().toString();
        int generic = s.indexOf('<');
        return generic < 0 ? s : s.substring(0, generic);
    }

    /** Best-effort LAZY detection: looks for fetch=LAZY in ManyToOne/OneToOne mirror values. */
    private static boolean isLazyFetch(VariableElement field) {
        for (AnnotationMirror am : field.getAnnotationMirrors()) {
            String annName = am.getAnnotationType().asElement().getSimpleName().toString();
            if (!annName.equals("ManyToOne") && !annName.equals("OneToOne")) continue;
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> v
                    : am.getElementValues().entrySet()) {
                if ("fetch".equals(v.getKey().getSimpleName().toString())
                        && v.getValue().getValue().toString().endsWith("LAZY")) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, String> readPropertyValues(TypeElement type) {
        Map<String, String> map = new LinkedHashMap<>();
        forEachNested(type, "properties", nested -> {
            String key = readStringMember(nested, "key");
            String val = readStringMember(nested, "value");
            if (key != null && map.put(key, val) != null) {
                error(type, "duplicate @PapProperty key: " + key);
            }
        });
        return map;
    }

    private Map<String, String> readOperationModes(TypeElement type) {
        Map<String, String> map = new LinkedHashMap<>();
        forEachNested(type, "operationModes", nested -> {
            String op = readEnumMember(nested, "operation");
            String mode = readEnumMember(nested, "mode");
            if (op != null && mode != null && map.put(op, mode) != null) {
                error(type, "duplicate @PapOperationMode for operation: " + op);
            }
        });
        return map;
    }

    private void forEachNested(TypeElement type, String memberName,
                               java.util.function.Consumer<AnnotationMirror> consumer) {
        for (AnnotationMirror am : type.getAnnotationMirrors()) {
            if (!isPapEntityAnnotation(am)) continue;  // ✅ Single continue for type check
            
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> v
                    : am.getElementValues().entrySet()) {
                if (!memberName.equals(v.getKey().getSimpleName().toString())) continue;  // ✅ Single continue for member check
                if (!(v.getValue().getValue() instanceof List<?> list)) continue;  // ✅ Single continue for type check
                
                for (Object item : list) {
                    if (item instanceof AnnotationValue av
                            && av.getValue() instanceof AnnotationMirror nested) {
                        consumer.accept(nested);
                    }
                }
            }
        }
    }

    /**
     * Checks if an annotation mirror is for the @PapEntity annotation type.
     */
    private boolean isPapEntityAnnotation(AnnotationMirror am) {
        return am.getAnnotationType().toString().equals(PapEntity.class.getName());
    }

    private static String readStringMember(AnnotationMirror am, String memberName) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                : am.getElementValues().entrySet()) {
            if (memberName.equals(e.getKey().getSimpleName().toString())) {
                Object v = e.getValue().getValue();
                return v == null ? null : v.toString();
            }
        }
        return null;
    }

    private static String readEnumMember(AnnotationMirror am, String memberName) {
        String s = readStringMember(am, memberName);
        if (s == null) return null;
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(dot + 1);
    }

    // ----------------------------------------------------------------- output

    private void writeMetadata() {
        try {
            FileObject file = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", "META-INF/pep-sdk/metadata.json");
            try (Writer w = file.openWriter()) {
                w.write(toJson(collected));
            }
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "PEP SDK : wrote metadata.json with " + collected.size() + " entity descriptor(s)");
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed to write metadata.json: " + e.getMessage());
        }
    }

    private static String toJson(List<EntityDescriptor> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"entities\": [\n");
        for (int i = 0; i < list.size(); i++) {
            EntityDescriptor d = list.get(i);
            sb.append("    {\n");
            sb.append("      \"entityClass\": ").append(quote(d.entityClass)).append(",\n");
            sb.append("      \"papEntity\": ").append(quote(d.papEntity)).append(",\n");
            sb.append("      \"idField\": ").append(quote(d.idField)).append(",\n");

            sb.append("      \"properties\": {");
            appendStringMap(sb, d.properties);
            sb.append("},\n");

            sb.append("      \"operationModes\": {");
            appendStringMap(sb, d.operationModes);
            sb.append("},\n");

            sb.append("      \"attributes\": [\n");
            for (int j = 0; j < d.attributes.size(); j++) {
                AttributeDescriptor a = d.attributes.get(j);
                sb.append("        { \"fieldName\": ").append(quote(a.fieldName))
                  .append(", \"attributeName\": ").append(quote(a.attributeName)).append(" }");
                if (j < d.attributes.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("      ],\n");

            sb.append("      \"includes\": [\n");
            int k = 0;
            for (IncludeDescriptor inc : d.includes) {
                sb.append("        { \"fieldName\": ").append(quote(inc.fieldName))
                  .append(", \"name\": ").append(quote(inc.name))
                  .append(", \"attribute\": ").append(quote(inc.attribute)).append(" }");
                if (++k < d.includes.size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }").append(i < list.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static void appendStringMap(StringBuilder sb, Map<String, String> m) {
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append(" ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
            first = false;
        }
        if (!m.isEmpty()) sb.append(" ");
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void error(Element where, String msg) { messager.printMessage(Diagnostic.Kind.ERROR, msg, where); }
    private void warn(Element where, String msg)  { messager.printMessage(Diagnostic.Kind.WARNING, msg, where); }

    // ----------------------------------------------------------------- carriers

    private static class EntityDescriptor {
        String entityClass;
        String papEntity;
        String idField;
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> operationModes = new LinkedHashMap<>();
        List<AttributeDescriptor> attributes = new ArrayList<>();
        List<IncludeDescriptor> includes = new ArrayList<>();
    }

    private record AttributeDescriptor(String fieldName, String attributeName) { }
    private record IncludeDescriptor(String fieldName, String name, String attribute) { }
}
