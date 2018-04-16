package com.mricefox.archmage.processor;

import com.mricefox.archmage.annotation.Module;
import com.mricefox.archmage.annotation.ServiceImpl;
import com.mricefox.archmage.annotation.Target;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * <p>Author:MrIcefox
 * <p>Email:extremetsa@gmail.com
 * <p>Description:
 * <p>Date:2018/1/24
 */
@SupportedOptions(value = {"archmage_module_packageName"})
public class AnnoProcessor extends AbstractProcessor {
    private static final String WARNING = "/**GENERATED BY ARCHMAGE, DO NOT MODIFY*/";

    private String packageName;

    private int round;

    private Messager messager;
    private Elements elementUtils;
    private Types typeUtils;

    private Map<String, String> targetsMapping = new LinkedHashMap<>();
    private String group;
    private String targetProviderClassName;

    private Map<String, String> servicesMapping = new LinkedHashMap<>();

    private String moduleClassName;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        long start = System.currentTimeMillis();

        messager = processingEnv.getMessager();
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();

        packageName = processingEnv.getOptions().get("archmage_module_packageName");
        String activatorClassName = packageName + ".Activator_$$_";

        round++;
        messager.printMessage(Diagnostic.Kind.NOTE, "Processing round " + round + ", new annotations: " +
                !annotations.isEmpty() + ", processingOver: " + roundEnv.processingOver());

        if (roundEnv.processingOver() && !annotations.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Unexpected processing state: annotations still available after processing over");
            return false;
        }
        //no annotations to process
        if (annotations.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.NOTE, "annotations is empty");
            return false;
        }
        messager.printMessage(Diagnostic.Kind.NOTE, "activatorClassName: " + activatorClassName);
        messager.printMessage(Diagnostic.Kind.NOTE, "packageName: " + packageName);

        //-------------- generate class file begin
        boolean collected = collectTargets(roundEnv);
        if (collected) {
            targetProviderClassName = writeTargetProvider();
        }

        collectService(roundEnv);

        findModule(roundEnv);

        writeActivator();
        //-------------- generate class file end

        messager.printMessage(Diagnostic.Kind.NOTE, "Processing round " + round + ", cost time: " +
                (System.currentTimeMillis() - start) + "ms");
        return true;
    }

    private boolean collectTargets(RoundEnvironment roundEnv) {
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Target.class);

        if (elements.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.WARNING, "No @Target");
            return false;
        }

        if (!elements.stream().allMatch(element -> element instanceof TypeElement)) {
            throw new AnnoProcessException("@Target must apply to class");
        }

        elements.stream()
                .map(e -> (TypeElement) e)
                .forEach(e -> {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "Handle @Target element: " + e.getQualifiedName());
                    String s = e.getAnnotation(Target.class).path();// /hotel/detail
                    if (s.trim().isEmpty()) {
                        throw new AnnoProcessException("Empty path is not permitted.");
                    }
                    if (!s.startsWith("/")) {
                        throw new AnnoProcessException("Path should start with /");
                    }
                    String[] segments = s.split("/");
                    if (segments.length != 3
                            || !segments[0].isEmpty()
                            || segments[1].trim().isEmpty()
                            || segments[2].trim().isEmpty()) {
                        throw new AnnoProcessException("Illegal path:" + s);
                    }
                    String g = segments[1];
                    String p = segments[2];
                    String target = e.getQualifiedName().toString();
                    if (group != null) {
                        if (!g.equals(group)) {
                            throw new AnnoProcessException("All path declared with @Target in one "
                                    + "module should start with same group");
                        }
                    } else {
                        group = g;
                    }

                    if (targetsMapping.containsKey(p)) {
                        throw new AnnoProcessException("Duplicate path:" + p);
                    }

                    if (targetsMapping.containsValue(target)) {
                        throw new AnnoProcessException("Duplicate target:" + target);
                    }

                    targetsMapping.put(p, target);
                });

        return true;
    }

    private String writeTargetProvider() {
        String simpleName = "TargetProvider_$$_";
        String className = packageName + "." + simpleName;
        BufferedWriter writer = null;

        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(className);
            writer = new BufferedWriter(sourceFile.openWriter());

            writer.append("package " + packageName + ";")
                    .append("import com.mricefox.archmage.runtime.ITargetProvider;")

                    .append(WARNING)
                    .append(String.format("public class %s implements ITargetProvider {", simpleName))

                    //group
                    .append("@Override ")
                    .append("public String group() {")
                    .append(String.format("return \"%s\";", group))
                    .append("}")

                    //mappings
                    .append("@Override ")
                    .append("public Class<?> bindTargets(String path) {");

            for (Map.Entry<String, String> entry : targetsMapping.entrySet()) {
                writer.append(String.format("if (\"%s\".equals(path)) {", entry.getKey()))
                        .append(String.format("return %s.class;}", entry.getValue()));
            }
            writer.append("return null;}}");

            return className;
        } catch (IOException e) {
            throw new AnnoProcessException("Could not write source for " + className, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    private void collectService(RoundEnvironment roundEnv) {
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(ServiceImpl.class);

        if (elements.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.WARNING, "No @ServiceImpl");
            return;
        }

        if (!elements.stream().allMatch(element -> element instanceof TypeElement)) {
            throw new AnnoProcessException("@ServiceImpl must apply to class");
        }

        elements.stream()
                .map(e -> (TypeElement) e)
                .forEach(e -> {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "Handle @ServiceImpl element: " + e.getQualifiedName());
                    //impl must implements a interface which extends IService
                    List<TypeMirror> serviceTypes = typeUtils.directSupertypes(e.asType())
                            .stream()
                            .filter(superType -> isDirectSubTypeOfClass(superType,
                                    "com.mricefox.archmage.runtime.IService"))
                            .collect(Collectors.toList());

                    if (serviceTypes.isEmpty()) {
                        throw new AnnoProcessException(e.getQualifiedName()
                                + " Class with annotation @ServiceImpl must implements a interface which extends IService");
                    }

                    if (serviceTypes.size() != 1) {
                        throw new AnnoProcessException(e.getQualifiedName()
                                + " Each impl can only implements 1 service");
                    }
                    String service = serviceTypes.iterator().next().toString();
                    String impl = e.getQualifiedName().toString();

                    if (servicesMapping.containsKey(service)) {
                        throw new AnnoProcessException("Duplicate service alias:" + service);
                    }
                    if (servicesMapping.containsValue(impl)) {
                        throw new AnnoProcessException("Impl:" + impl + " already registered");
                    }
                    servicesMapping.put(service, impl);
                });
    }

    /**
     * t is direct sub type of class with canonicalName
     */
    private boolean isDirectSubTypeOfClass(TypeMirror t, String canonicalName) {
        TypeElement targetType = elementUtils.getTypeElement(canonicalName);

        return typeUtils.directSupertypes(t).stream()
                .anyMatch(superType ->
                        typeUtils.isSameType(superType, targetType.asType()));

//        return processingEnv.getTypeUtils().isSubtype(t, superType.asType());
    }

    private void findModule(RoundEnvironment roundEnv) {
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Module.class);

        if (elements.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.WARNING, "No @Module");
            return;
        }

        if (!elements.stream().allMatch(element -> element instanceof TypeElement)) {
            throw new AnnoProcessException("@Module must apply to class");
        }

        if (elements.size() > 1) {
            throw new AnnoProcessException("For each project, @Module can only apply to 1 class");
        }

        TypeElement element = (TypeElement) elements.iterator().next();

        messager.printMessage(Diagnostic.Kind.NOTE,
                "Handle @Module element: " + element.getQualifiedName());

        if (!isDirectSubTypeOfClass(element.asType(),
                "com.mricefox.archmage.runtime.ArchmageModule")) {
            throw new AnnoProcessException(element.getQualifiedName()
                    + " Class with annotation @Module must extends ArchmageModule");
        }

        moduleClassName = element.getQualifiedName().toString();
    }

    private void writeActivator() {
        String simpleName = "Activator_$$_";
        String className = packageName + "." + simpleName;
        BufferedWriter writer = null;

        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(className);
            writer = new BufferedWriter(sourceFile.openWriter());

            writer.append("package " + packageName + ";")
                    .append("import android.app.Application;")
                    .append("import com.mricefox.archmage.runtime.ModuleActivator;")
                    .append(WARNING)
                    .append(String.format("public class %s extends ModuleActivator {", simpleName))
                    .append("@Override ")
                    .append("protected void attach(Application application) {");

            for (Map.Entry<String, String> entry : servicesMapping.entrySet()) {
                writer.append(String.format("registerService(%s.class, new %s());"
                        , entry.getKey(), entry.getValue()));
            }

            if (moduleClassName != null) {
                writer.append(String.format("registerModule(new %s());", moduleClassName));
            }

            if (targetProviderClassName != null) {
                writer.append(String.format("registerTargetProvider(new %s());", targetProviderClassName));
            }
            writer.append("}}");
        } catch (IOException e) {
            throw new AnnoProcessException("Could not write source for " + className, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }


    @SuppressWarnings("unchecked")
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return new LinkedHashSet() {{
            add(Target.class.getName());
            add(ServiceImpl.class.getName());
            add(Module.class.getName());
        }};
    }
}
