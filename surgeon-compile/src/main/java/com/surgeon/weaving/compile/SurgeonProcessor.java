
package com.surgeon.weaving.compile;

import com.google.auto.common.SuperficialValidation;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import com.surgeon.weaving.annotations.Replace;
import com.surgeon.weaving.annotations.ReplaceAfter;
import com.surgeon.weaving.annotations.ReplaceBefore;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import static com.surgeon.weaving.compile.Utils.getFullTypesString;
import static com.surgeon.weaving.compile.Utils.isEmpty;

public class SurgeonProcessor extends AbstractProcessor {

    private static final String FILE_DOC = "DO NOT EDIT THIS FILE!!! IT WAS GENERATED BY SURGEON.";
    private static final String PACKAGE_NAME = "com.surgeon.weaving.masters";
    private static final String PREFIX = "Master_";

    private static final ClassName ISurgeonMaster = ClassName.get("com.surgeon.weaving.core.interfaces", "IMaster");
    private static final ClassName SurgeonMethod = ClassName.get("com.surgeon.weaving.core", "SurgeonMethod");

    private Elements elementUtils;
    private Filer filer;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        elementUtils = env.getElementUtils();
        filer = env.getFiler();
    }

    @Override
    public Set<String> getSupportedOptions() {
        return super.getSupportedOptions();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
            types.add(annotation.getCanonicalName());
        }
        return types;
    }

    private Set<Class<? extends Annotation>> getSupportedAnnotations() {
        Set<Class<? extends Annotation>> annotations = new LinkedHashSet<>();
        annotations.add(Replace.class);
        annotations.add(ReplaceAfter.class);
        annotations.add(ReplaceBefore.class);
        return annotations;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment env) {
        List<JavaFile> files = findAndParseTargets(env);
        for (JavaFile javaFile : files) {
            try {
                javaFile.writeTo(filer);
            } catch (IOException e) {
                error("Unable to write same name %s: %s", javaFile.packageName, e.getMessage());
            }
        }
        return false;
    }

    private void error(String message, Object... args) {
        printMessage(Diagnostic.Kind.ERROR, message, args);
    }

    private void note(String message, Object... args) {
        printMessage(Diagnostic.Kind.NOTE, message, args);
    }

    private void printMessage(Diagnostic.Kind kind, String message, Object[] args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        processingEnv.getMessager().printMessage(kind, message);
    }

    private List<JavaFile> findAndParseTargets(RoundEnvironment env) {
        List<JavaFile> javaFiles = new ArrayList<>();

        // Process each @Replace element.
        Map<String, Map<String, Element>> group = new HashMap<>();
        for (Element e : env.getElementsAnnotatedWith(Replace.class)) {
            if (!SuperficialValidation.validateElement(e)) {
                continue;
            }
            Replace replace = e.getAnnotation(Replace.class);
            parseReplace(e, group, "", replace.namespace(), replace.function());
        }

        // Process each @ReplaceBefore element.
        for (Element e : env.getElementsAnnotatedWith(ReplaceBefore.class)) {
            if (!SuperficialValidation.validateElement(e)) {
                continue;
            }
            ReplaceBefore replace = e.getAnnotation(ReplaceBefore.class);
            parseReplace(e, group, "before_", replace.namespace(), replace.function());
        }

        // Process each @ReplaceAfter element.
        for (Element e : env.getElementsAnnotatedWith(ReplaceAfter.class)) {
            if (!SuperficialValidation.validateElement(e)) {
                continue;
            }
            ReplaceAfter replace = e.getAnnotation(ReplaceAfter.class);
            parseReplace(e, group, "after_", replace.namespace(), replace.function());
        }

        generateMasterJavaFile(group, javaFiles);
        return javaFiles;
    }

    private void parseReplace(
            Element e,
            Map<String, Map<String, Element>> group,
            String prefix,
            String namespace,
            String function) {
        if (isEmpty(namespace) || isEmpty(function)) {
            error("namespace=%s or function=%s can't be empty.", namespace, function);
        }

        String fullName = prefix + function;
        Map<String, Element> collect;
        if (group.containsKey(namespace)) {
            collect = group.get(namespace);
            if (collect.containsKey(fullName)) {
                error("duplicate define %s.%s", namespace, function);
            }
        } else {
            collect = new HashMap<>();
        }
        collect.put(fullName, e);
        group.put(namespace, collect);
    }

    private void generateMasterJavaFile(Map<String, Map<String, Element>> groups, List<JavaFile> javaFiles) {
        Set<Map.Entry<String, Map<String, Element>>> kvs = groups.entrySet();
        for (Map.Entry<String, Map<String, Element>> group : kvs) {
            String namespace = group.getKey();
            if (isEmpty(namespace)) return;

            Map<String, Element> methodMappings = group.getValue();

            // constructor build
            MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder();
            constructorBuilder.addModifiers(Modifier.PUBLIC).addException(Throwable.class);

            // constructor body
            constructorBuilder.addStatement("this.mapping = new $T()", HashMap.class);

            for (Map.Entry<String, Element> mapping : methodMappings.entrySet()) {
                String fullName = mapping.getKey();//method name + "." + extra
                Element element = mapping.getValue();

                SurgeonMethod sm = parseToSurgeonMethod(element);
                sm.owner = ClassName.get(((TypeElement) element.getEnclosingElement()));

                // add method
                constructorBuilder.addStatement(
                        "mapping.put($S," + "new $T($T.class," + sm.method + ",$S,$S)" + ")",
                        fullName,
                        SurgeonMethod,
                        sm.owner,
                        //method inner
                        sm.owner,
                        element.getSimpleName().toString(),
                        //method inner end
                        sm.simpleParamsName,
                        sm.simpleParamsTypes);
                //.addCode("\n");
            }

            // method build
            MethodSpec.Builder invokeBuilder = MethodSpec.methodBuilder("find");
            invokeBuilder.addModifiers(Modifier.PUBLIC)
                    .returns(SurgeonMethod)
                    .addParameter(String.class, "name");

            // method body
            invokeBuilder.addStatement("return ($T) mapping.get(name)", SurgeonMethod);

            // java file build
            String mirror_name_main = PREFIX + namespace.replace(".", "_");
            TypeSpec clazz = TypeSpec.classBuilder(mirror_name_main)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL).addSuperinterface(ISurgeonMaster)
                    // Fields
                    .addFields(buildRouterModuleFields())
                    // constructor
                    .addMethod(constructorBuilder.build())
                    // Methods
                    .addMethod(invokeBuilder.build())
                    // doc
                    .addJavadoc(FILE_DOC)
                    .build();

            JavaFile javaFile = JavaFile.builder(PACKAGE_NAME, clazz).build();
            javaFiles.add(javaFile);
        }
    }

    private SurgeonMethod parseToSurgeonMethod(Element element) {
        SurgeonMethod method = new SurgeonMethod();
        String args = ((ExecutableElement) element).getParameters().toString();
        String types = "";
        String additionParamsTypes = element.toString();
        int start = additionParamsTypes.indexOf("(");
        int end = additionParamsTypes.indexOf(")");
        if (end - start > 1) {
            // open1(java.lang.Object) => java.lang.Object.class)
            types = additionParamsTypes.substring(start + 1, end);
            if (types.lastIndexOf("...") != -1)
                types = types.replace("...", "[]");
            additionParamsTypes = getFullTypesString(types) + ")";
        } else {
            additionParamsTypes = ")";
        }

        //TODO maybe used in future
        //method.simpleParamsName = args;
        //method.simpleParamsTypes = types;
        method.simpleParamsName = "";
        method.simpleParamsTypes = "";
        method.method = "$T.class.getMethod($S," + additionParamsTypes;
        return method;
    }

    // build fields
    private Iterable<FieldSpec> buildRouterModuleFields() {
        ArrayList<FieldSpec> fieldSpecs = new ArrayList<>();
        FieldSpec f_mapping = FieldSpec.builder(HashMap.class, "mapping")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();
        fieldSpecs.add(f_mapping);
        return fieldSpecs;
    }
}
