package dev.nanonative.railix.creator;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.util.HashSet;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Build-time static reachability. Method bodies are copied, never interpreted or rewritten. */
final class RuntimeClassDependencies {
    private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([^;]+);");

    private RuntimeClassDependencies() { }

    static Selection select(final ClassModel original, final byte[] bytes, final Set<Method> retained,
                            final boolean complete, final boolean provider,
                            final Map<Method, Set<Method>> calls, final ClassFile classFile) throws IOException {
        try {
            final String owner = original.thisClass().asInternalName() + ".class";
            final boolean all = complete;
            final Set<Method> closure = new HashSet<>(retained);
            if ((original.flags().flagsMask() & ClassFile.ACC_ENUM) != 0) {
                final String type = "L" + original.thisClass().asInternalName() + ";";
                closure.add(new Method(owner, "values", "()[" + type));
                closure.add(new Method(owner, "valueOf", "(Ljava/lang/String;)" + type));
            }
            original.methods().stream().filter(method -> (method.flags().flagsMask() & ClassFile.ACC_STATIC) == 0
                    || method.methodName().equalsString("<clinit>"))
                    .forEach(method -> closure.add(new Method(owner, method.methodName().stringValue(), method.methodType().stringValue())));
            final var pending = new ArrayDeque<>(closure);
            while (!all && !pending.isEmpty()) {
                for (final Method target : calls.getOrDefault(pending.removeFirst(), Set.of())) {
                    if (target.owner().equals(owner) && closure.add(target)) pending.add(target);
                    if (target.owner().equals("java/lang/invoke/LambdaMetafactory.class") && target.name().equals("altMetafactory")) {
                        final var deserialize = new Method(owner, "$deserializeLambda$", "(Ljava/lang/invoke/SerializedLambda;)Ljava/lang/Object;");
                        if (closure.add(deserialize)) pending.add(deserialize);
                    }
                }
            }
            final var keep = (java.util.function.Predicate<MethodModel>) method -> all
                    || closure.contains(new Method(owner, method.methodName().stringValue(), method.methodType().stringValue()));
            final boolean changed = original.methods().stream().anyMatch(keep.negate());
            final byte[] selected = changed ? classFile.transformClass(original,
                    ClassTransform.dropping(element -> element instanceof MethodModel method && !keep.test(method))) : bytes;
            // Sibling inventories and enclosing factories do not execute when a runtime class is used.
            final var model = !provider ? original : classFile.parse(classFile.transformClass(classFile.parse(selected),
                    ClassTransform.dropping(element -> element instanceof InnerClassesAttribute
                            || element instanceof NestMembersAttribute || element instanceof EnclosingMethodAttribute)));
            final Set<String> names = new TreeSet<>();
            for (final var attribute : original.attributes()) {
                if (attribute instanceof InnerClassesAttribute nested) {
                    nested.classes().stream().filter(info -> info.innerClass().asInternalName()
                            .equals(original.thisClass().asInternalName())).flatMap(info -> info.outerClass().stream())
                            .forEach(type -> names.add(type.asInternalName() + ".class"));
                } else if (attribute instanceof EnclosingMethodAttribute enclosing) {
                    names.add(enclosing.enclosingClass().asInternalName() + ".class");
                }
            }
            final Set<Method> methods = new HashSet<>();
            final Set<String> descriptors = metadata(model);
            model.fields().forEach(field -> descriptors.add(field.fieldType().stringValue()));
            model.methods().forEach(method -> descriptors.add(method.methodType().stringValue()));
            for (final var entry : model.constantPool()) {
                if (entry instanceof ClassEntry type) {
                    if (type.asInternalName().startsWith("[")) descriptors.add(type.asInternalName());
                    else names.add(type.asInternalName() + ".class");
                }
                if (entry instanceof MemberRefEntry member) {
                    final String descriptor = member.type().stringValue();
                    descriptors.add(descriptor);
                    if (descriptor.startsWith("(")) methods.add(new Method(member.owner().asInternalName() + ".class",
                            member.name().stringValue(), descriptor));
                }
                if (entry instanceof java.lang.classfile.constantpool.NameAndTypeEntry type) descriptors.add(type.type().stringValue());
                else if (entry instanceof java.lang.classfile.constantpool.MethodTypeEntry type) descriptors.add(type.descriptor().stringValue());
            }
            for (final String descriptor : descriptors) {
                final var matcher = DESCRIPTOR_CLASS.matcher(descriptor);
                while (matcher.find()) names.add(matcher.group(1) + ".class");
            }
            return new Selection(Set.copyOf(names), Set.copyOf(methods), selected);
        } catch (final java.io.UncheckedIOException exception) {
            throw exception.getCause();
        } catch (final IllegalArgumentException exception) {
            throw ApplicationBuilder.GeneratedCompilationException.invalidRuntimeClass(original.thisClass().asInternalName(), exception);
        }
    }

    /** Reads actual metadata types rather than guessing Java types from arbitrary UTF-8 strings. */
    static Set<String> metadata(final ClassModel model) {
        final Set<String> descriptors = new HashSet<>();
        final var pending = new ArrayDeque<Object>();
        pending.add(model);
        while (!pending.isEmpty()) {
            final Object item = pending.removeFirst();
            if (item instanceof java.lang.classfile.AttributedElement element) {
                if (item instanceof ClassModel type) { pending.addAll(type.fields()); pending.addAll(type.methods()); }
                else if (item instanceof MethodModel method) method.code().ifPresent(pending::add);
                else if (item instanceof java.lang.classfile.attribute.RecordComponentInfo component) descriptors.add(component.descriptor().stringValue());
                for (final var attribute : element.attributes()) {
                    if (attribute instanceof java.lang.classfile.attribute.SignatureAttribute signature) {
                        if (element instanceof ClassModel) pending.add(signature.asClassSignature());
                        else if (element instanceof MethodModel) pending.add(signature.asMethodSignature());
                        else pending.add(signature.asTypeSignature());
                    } else if (attribute instanceof java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute annotations) pending.addAll(annotations.annotations());
                    else if (attribute instanceof java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute annotations) pending.addAll(annotations.annotations());
                    else if (attribute instanceof java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute annotations) annotations.annotations().forEach(annotation -> pending.add(annotation.annotation()));
                    else if (attribute instanceof java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute annotations) annotations.annotations().forEach(annotation -> pending.add(annotation.annotation()));
                    else if (attribute instanceof java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute annotations) annotations.parameterAnnotations().forEach(pending::addAll);
                    else if (attribute instanceof java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute annotations) annotations.parameterAnnotations().forEach(pending::addAll);
                    else if (attribute instanceof java.lang.classfile.attribute.AnnotationDefaultAttribute value) pending.add(value.defaultValue());
                    else if (attribute instanceof java.lang.classfile.attribute.RecordAttribute record) pending.addAll(record.components());
                }
            } else if (item instanceof java.lang.classfile.ClassSignature signature) {
                pending.add(signature.superclassSignature()); pending.addAll(signature.superinterfaceSignatures()); pending.addAll(signature.typeParameters());
            } else if (item instanceof java.lang.classfile.MethodSignature signature) {
                pending.add(signature.result()); pending.addAll(signature.arguments()); pending.addAll(signature.throwableSignatures()); pending.addAll(signature.typeParameters());
            } else if (item instanceof java.lang.classfile.Signature.ClassTypeSig type) {
                descriptors.add(type.classDesc().descriptorString()); type.outerType().ifPresent(pending::add); pending.addAll(type.typeArgs());
            } else if (item instanceof java.lang.classfile.Signature.ArrayTypeSig type) pending.add(type.componentSignature());
            else if (item instanceof java.lang.classfile.Signature.TypeArg.Bounded type) pending.add(type.boundType());
            else if (item instanceof java.lang.classfile.Signature.TypeParam type) { type.classBound().ifPresent(pending::add); pending.addAll(type.interfaceBounds()); }
            else if (item instanceof java.lang.classfile.Annotation annotation) {
                descriptors.add(annotation.className().stringValue()); annotation.elements().forEach(element -> pending.add(element.value()));
            } else if (item instanceof java.lang.classfile.AnnotationValue.OfClass type) descriptors.add(type.className().stringValue());
            else if (item instanceof java.lang.classfile.AnnotationValue.OfEnum type) descriptors.add(type.className().stringValue());
            else if (item instanceof java.lang.classfile.AnnotationValue.OfAnnotation annotation) pending.add(annotation.annotation());
            else if (item instanceof java.lang.classfile.AnnotationValue.OfArray array) pending.addAll(array.values());
        }
        return descriptors;
    }

    static Map<Method, Set<Method>> calls(final ClassModel model) {
        final Map<Method, Set<Method>> calls = new java.util.HashMap<>();
        final String owner = model.thisClass().asInternalName() + ".class";
        for (final var method : model.methods()) {
            final Set<Method> targets = new HashSet<>();
            final var constants = new ArrayDeque<java.lang.classfile.constantpool.PoolEntry>();
            if (method.code().isPresent()) {
                for (final var element : method.code().orElseThrow()) {
                    if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke) constants.add(invoke.method());
                    else if (element instanceof java.lang.classfile.instruction.InvokeDynamicInstruction dynamic) constants.add(dynamic.invokedynamic());
                    else if (element instanceof java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction constant) constants.add(constant.constantEntry());
                }
            }
            final Set<java.lang.classfile.constantpool.PoolEntry> visited = new HashSet<>();
            while (!constants.isEmpty()) {
                final var constant = constants.removeFirst();
                if (!visited.add(constant)) continue;
                if (constant instanceof MemberRefEntry reference && reference.type().stringValue().startsWith("("))
                    targets.add(new Method(reference.owner().asInternalName() + ".class", reference.name().stringValue(), reference.type().stringValue()));
                else if (constant instanceof java.lang.classfile.constantpool.MethodHandleEntry handle) constants.add(handle.reference());
                else if (constant instanceof java.lang.classfile.constantpool.DynamicConstantPoolEntry dynamic) {
                    constants.add(dynamic.bootstrap().bootstrapMethod());
                    constants.addAll(dynamic.bootstrap().arguments());
                }
            }
            calls.put(new Method(owner, method.methodName().stringValue(), method.methodType().stringValue()), Set.copyOf(targets));
        }
        return Map.copyOf(calls);
    }

    static byte[] finish(final String name, final byte[] bytes, final Set<String> available, final ClassFile classFile) throws IOException {
        try {
            return classFile.transformClass(classFile.parse(bytes), (builder, element) -> {
                if (element instanceof InnerClassesAttribute nested) {
                    builder.with(InnerClassesAttribute.of(nested.classes().stream().filter(info ->
                            available.contains(info.innerClass().asInternalName() + ".class")
                                    && info.outerClass().map(type -> available.contains(type.asInternalName() + ".class")).orElse(true)).toList()));
                } else if (element instanceof NestMembersAttribute nest) {
                    builder.with(NestMembersAttribute.of(nest.nestMembers().stream()
                            .filter(type -> available.contains(type.asInternalName() + ".class")).toList()));
                } else builder.with(element);
            });
        } catch (final java.io.UncheckedIOException exception) {
            throw exception.getCause();
        } catch (final IllegalArgumentException exception) {
            throw ApplicationBuilder.GeneratedCompilationException.invalidRuntimeClass(name, exception);
        }
    }

    static boolean systemClass(final String entry, final java.util.Collection<java.lang.module.ModuleReference> modules)
            throws IOException {
        final int slash = entry.lastIndexOf('/');
        final String packageName = slash < 0 ? "" : entry.substring(0, slash).replace('/', '.');
        for (final var module : modules) {
            if (!module.descriptor().packages().contains(packageName)) continue;
            try (var reader = module.open()) {
                if (reader.find(entry).isPresent()) return true;
            }
        }
        return false;
    }

    record Method(String owner, String name, String descriptor) { }
    record Selection(Set<String> classes, Set<Method> methods, byte[] bytes) { }
}
