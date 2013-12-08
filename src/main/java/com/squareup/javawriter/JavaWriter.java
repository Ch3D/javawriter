// Copyright 2013 Square, Inc.
package com.squareup.javawriter;

import static javax.lang.model.element.Modifier.ABSTRACT;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.element.Modifier;

/** A utility class which aids in generating Java source files. */
public class JavaWriter implements Closeable {
	private enum Scope {
		TYPE_DECLARATION, ABSTRACT_METHOD, NON_ABSTRACT_METHOD, CONSTRUCTOR, CONTROL_FLOW, ANNOTATION_ATTRIBUTE, ANNOTATION_ARRAY_VALUE, INITIALIZER, SWITCH
	}

	private static final Pattern TYPE_PATTERN = Pattern
			.compile("(?:[\\w$]+\\.)*([\\w\\.*$]+)");
	private static final int MAX_SINGLE_LINE_ATTRIBUTES = 3;

	private static final String INDENT = "  ";

	/** Map fully qualified type names to their short names. */
	private final Map<String, String> importedTypes = new LinkedHashMap<String, String>();
	private String packagePrefix;
	private final Deque<Scope> scopes = new ArrayDeque<Scope>();
	private final Deque<String> types = new ArrayDeque<String>();
	private final Writer out;
	private boolean isCompressingTypes = true;

	private String indent = INDENT;

	private static final EnumSet<Scope> METHOD_SCOPES = EnumSet.of(
			Scope.NON_ABSTRACT_METHOD, Scope.CONSTRUCTOR, Scope.CONTROL_FLOW,
			Scope.INITIALIZER, Scope.SWITCH);

	/**
	 * Returns the string literal representing {@code data}, including wrapping
	 * quotes.
	 */
	public static String stringLiteral(final String data) {
		final StringBuilder result = new StringBuilder();
		result.append('"');
		for (int i = 0; i < data.length(); i++) {
			final char c = data.charAt(i);
			switch (c) {
			case '"':
				result.append("\\\"");
				break;
			case '\\':
				result.append("\\\\");
				break;
			case '\b':
				result.append("\\b");
				break;
			case '\t':
				result.append("\\t");
				break;
			case '\n':
				result.append("\\n");
				break;
			case '\f':
				result.append("\\f");
				break;
			case '\r':
				result.append("\\r");
				break;
			default:
				if (Character.isISOControl(c)) {
					result.append(String.format("\\u%04x", (int) c));
				} else {
					result.append(c);
				}
			}
		}
		result.append('"');
		return result.toString();
	}

	/**
	 * Build a string representation of a type and optionally its generic type
	 * arguments.
	 */
	public static String type(final Class<?> raw, final String... parameters) {
		if (parameters.length == 0) {
			return raw.getCanonicalName();
		}
		if (raw.getTypeParameters().length != parameters.length) {
			throw new IllegalArgumentException();
		}
		final StringBuilder result = new StringBuilder();
		result.append(raw.getCanonicalName());
		result.append("<");
		result.append(parameters[0]);
		for (int i = 1; i < parameters.length; i++) {
			result.append(", ");
			result.append(parameters[i]);
		}
		result.append(">");
		return result.toString();
	}

	/**
	 * @param out
	 *            the stream to which Java source will be written. This should
	 *            be a buffered stream.
	 */
	public JavaWriter(final Writer out) {
		this.out = out;
	}

	public JavaWriter beginConstructor(final Set<Modifier> modifiers,
			final List<String> parameters, final List<String> throwsTypes)
			throws IOException {
		beginMethod(null, types.peekFirst(), modifiers, parameters, throwsTypes);
		return this;
	}

	public JavaWriter beginConstructor(final Set<Modifier> modifiers,
			final String... parameters) throws IOException {
		beginMethod(null, types.peekFirst(), modifiers, parameters);
		return this;
	}

	/**
	 * @param controlFlow
	 *            the control flow construct and its code, such as
	 *            "if (foo == 5)". Shouldn't contain braces or newline
	 *            characters.
	 */
	public JavaWriter beginControlFlow(final String controlFlow)
			throws IOException {
		checkInMethod();
		indent();
		out.write(controlFlow);
		out.write(" {\n");
		scopes.push(Scope.CONTROL_FLOW);
		return this;
	}

	/**
	 * Emits an initializer declaration.
	 * 
	 * @param isStatic
	 *            true if it should be an static initializer, false for an
	 *            instance initializer.
	 */
	public JavaWriter beginInitializer(final boolean isStatic)
			throws IOException {
		indent();
		if (isStatic) {
			out.write("static");
			out.write(" {\n");
		} else {
			out.write("{\n");
		}
		scopes.push(Scope.INITIALIZER);
		return this;
	}

	/**
	 * Emit a method declaration.
	 * 
	 * <p>
	 * A {@code null} return type may be used to indicate a constructor, but
	 * {@link #beginConstructor(Set, List, List)} should be preferred. This
	 * behavior may be removed in a future release.
	 * 
	 * @param returnType
	 *            the method's return type, or null for constructors.
	 * @param name
	 *            the method name, or the fully qualified class name for
	 *            constructors.
	 * @param modifiers
	 *            the set of modifiers to be applied to the method
	 * @param parameters
	 *            alternating parameter types and names.
	 * @param throwsTypes
	 *            the classes to throw, or null for no throws clause.
	 */
	public JavaWriter beginMethod(final String returnType, final String name,
			final Set<Modifier> modifiers, final List<String> parameters,
			final List<String> throwsTypes) throws IOException {
		indent();
		emitModifiers(modifiers);
		if (returnType != null) {
			emitCompressedType(returnType);
			out.write(" ");
			out.write(name);
		} else {
			emitCompressedType(name);
		}
		out.write("(");
		if (parameters != null) {
			for (int p = 0; p < parameters.size();) {
				if (p != 0) {
					out.write(", ");
				}
				emitCompressedType(parameters.get(p++));
				out.write(" ");
				emitCompressedType(parameters.get(p++));
			}
		}
		out.write(")");
		if ((throwsTypes != null) && (throwsTypes.size() > 0)) {
			out.write("\n");
			indent();
			out.write("    throws ");
			for (int i = 0; i < throwsTypes.size(); i++) {
				if (i != 0) {
					out.write(", ");
				}
				emitCompressedType(throwsTypes.get(i));
			}
		}
		if (modifiers.contains(ABSTRACT)) {
			out.write(";\n");
			scopes.push(Scope.ABSTRACT_METHOD);
		} else {
			out.write(" {\n");
			scopes.push(returnType == null ? Scope.CONSTRUCTOR
					: Scope.NON_ABSTRACT_METHOD);
		}
		return this;
	}

	/**
	 * Emit a method declaration.
	 * 
	 * <p>
	 * A {@code null} return type may be used to indicate a constructor, but
	 * {@link #beginConstructor(Set, String...)} should be preferred. This
	 * behavior may be removed in a future release.
	 * 
	 * @param returnType
	 *            the method's return type, or null for constructors
	 * @param name
	 *            the method name, or the fully qualified class name for
	 *            constructors.
	 * @param modifiers
	 *            the set of modifiers to be applied to the method
	 * @param parameters
	 *            alternating parameter types and names.
	 */
	public JavaWriter beginMethod(final String returnType, final String name,
			final Set<Modifier> modifiers, final String... parameters)
			throws IOException {
		return beginMethod(returnType, name, modifiers,
				Arrays.asList(parameters), null);
	}

	public JavaWriter beginSwitch(final String param) throws IOException {
		indent();
		scopes.push(Scope.SWITCH);
		out.write("switch(");
		out.write(param);
		out.write(") {\n");
		return this;
	}

	/**
	 * Emits a type declaration.
	 * 
	 * @param kind
	 *            such as "class", "interface" or "enum".
	 */
	public JavaWriter beginType(final String type, final String kind)
			throws IOException {
		return beginType(type, kind, EnumSet.noneOf(Modifier.class), null);
	}

	/**
	 * Emits a type declaration.
	 * 
	 * @param kind
	 *            such as "class", "interface" or "enum".
	 */
	public JavaWriter beginType(final String type, final String kind,
			final Set<Modifier> modifiers) throws IOException {
		return beginType(type, kind, modifiers, null);
	}

	/**
	 * Emits a type declaration.
	 * 
	 * @param kind
	 *            such as "class", "interface" or "enum".
	 * @param extendsType
	 *            the class to extend, or null for no extends clause.
	 */
	public JavaWriter beginType(final String type, final String kind,
			final Set<Modifier> modifiers, final String extendsType,
			final String... implementsTypes) throws IOException {
		indent();
		emitModifiers(modifiers);
		out.write(kind);
		out.write(" ");
		emitCompressedType(type);
		if (extendsType != null) {
			out.write(" extends ");
			emitCompressedType(extendsType);
		}
		if (implementsTypes.length > 0) {
			out.write("\n");
			indent();
			out.write("    implements ");
			for (int i = 0; i < implementsTypes.length; i++) {
				if (i != 0) {
					out.write(", ");
				}
				emitCompressedType(implementsTypes[i]);
			}
		}
		out.write(" {\n");
		scopes.push(Scope.TYPE_DECLARATION);
		types.push(type);
		return this;
	}

	private void checkInMethod() {
		if (!METHOD_SCOPES.contains(scopes.peekFirst())) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public void close() throws IOException {
		out.close();
	}

	/** Try to compress a fully-qualified class name to only the class name. */
	public String compressType(final String type) {
		final StringBuilder sb = new StringBuilder();
		if (packagePrefix == null) {
			throw new IllegalStateException();
		}

		final Matcher m = TYPE_PATTERN.matcher(type);
		int pos = 0;
		while (true) {
			final boolean found = m.find(pos);

			// Copy non-matching characters like "<".
			final int typeStart = found ? m.start() : type.length();
			sb.append(type, pos, typeStart);

			if (!found) {
				break;
			}

			// Copy a single class name, shortening it if possible.
			final String name = m.group(0);
			final String imported = importedTypes.get(name);
			if (imported != null) {
				sb.append(imported);
			} else if (isClassInPackage(name)) {
				final String compressed = name
						.substring(packagePrefix.length());
				if (isAmbiguous(compressed)) {
					sb.append(name);
				} else {
					sb.append(compressed);
				}
			} else if (name.startsWith("java.lang.")) {
				sb.append(name.substring("java.lang.".length()));
			} else {
				sb.append(name);
			}
			pos = m.end();
		}
		return sb.toString();
	}

	private boolean containsArray(final Collection<?> values) {
		for (final Object value : values) {
			if (value instanceof Object[]) {
				return true;
			}
		}
		return false;
	}

	/** Equivalent to {@code annotation(annotationType.getName(), emptyMap())}. */
	public JavaWriter emitAnnotation(
			final Class<? extends Annotation> annotationType)
			throws IOException {
		return emitAnnotation(type(annotationType),
				Collections.<String, Object> emptyMap());
	}

	/** Equivalent to {@code annotation(annotationType.getName(), attributes)}. */
	public JavaWriter emitAnnotation(
			final Class<? extends Annotation> annotationType,
			final Map<String, ?> attributes) throws IOException {
		return emitAnnotation(type(annotationType), attributes);
	}

	/**
	 * Annotates the next element with {@code annotationType} and a
	 * {@code value}.
	 * 
	 * @param value
	 *            an object used as the default (value) parameter of the
	 *            annotation. The value will be encoded using Object.toString();
	 *            use {@link #stringLiteral} for String values. Object arrays
	 *            are written one element per line.
	 */
	public JavaWriter emitAnnotation(
			final Class<? extends Annotation> annotationType, final Object value)
			throws IOException {
		return emitAnnotation(type(annotationType), value);
	}

	/** Equivalent to {@code annotation(annotation, emptyMap())}. */
	public JavaWriter emitAnnotation(final String annotation)
			throws IOException {
		return emitAnnotation(annotation,
				Collections.<String, Object> emptyMap());
	}

	/**
	 * Annotates the next element with {@code annotation} and {@code attributes}
	 * .
	 * 
	 * @param attributes
	 *            a map from annotation attribute names to their values. Values
	 *            are encoded using Object.toString(); use
	 *            {@link #stringLiteral} for String values. Object arrays are
	 *            written one element per line.
	 */
	public JavaWriter emitAnnotation(final String annotation,
			final Map<String, ?> attributes) throws IOException {
		indent();
		out.write("@");
		emitCompressedType(annotation);
		switch (attributes.size()) {
		case 0:
			break;
		case 1:
			final Entry<String, ?> onlyEntry = attributes.entrySet().iterator()
					.next();
			out.write("(");
			if (!"value".equals(onlyEntry.getKey())) {
				out.write(onlyEntry.getKey());
				out.write(" = ");
			}
			emitAnnotationValue(onlyEntry.getValue());
			out.write(")");
			break;
		default:
			final boolean split = (attributes.size() > MAX_SINGLE_LINE_ATTRIBUTES)
					|| containsArray(attributes.values());
			out.write("(");
			scopes.push(Scope.ANNOTATION_ATTRIBUTE);
			String separator = split ? "\n" : "";
			for (final Map.Entry<String, ?> entry : attributes.entrySet()) {
				out.write(separator);
				separator = split ? ",\n" : ", ";
				if (split) {
					indent();
				}
				out.write(entry.getKey());
				out.write(" = ");
				final Object value = entry.getValue();
				emitAnnotationValue(value);
			}
			popScope(Scope.ANNOTATION_ATTRIBUTE);
			if (split) {
				out.write("\n");
				indent();
			}
			out.write(")");
			break;
		}
		out.write("\n");
		return this;
	}

	/**
	 * Annotates the next element with {@code annotation} and a {@code value}.
	 * 
	 * @param value
	 *            an object used as the default (value) parameter of the
	 *            annotation. The value will be encoded using Object.toString();
	 *            use {@link #stringLiteral} for String values. Object arrays
	 *            are written one element per line.
	 */
	public JavaWriter emitAnnotation(final String annotation, final Object value)
			throws IOException {
		indent();
		out.write("@");
		emitCompressedType(annotation);
		out.write("(");
		emitAnnotationValue(value);
		out.write(")");
		out.write("\n");
		return this;
	}

	/**
	 * Writes a single annotation value. If the value is an array, each element
	 * in the array will be written to its own line.
	 */
	private JavaWriter emitAnnotationValue(final Object value)
			throws IOException {
		if (value instanceof Object[]) {
			out.write("{");
			boolean firstValue = true;
			scopes.push(Scope.ANNOTATION_ARRAY_VALUE);
			for (final Object o : ((Object[]) value)) {
				if (firstValue) {
					firstValue = false;
					out.write("\n");
				} else {
					out.write(",\n");
				}
				indent();
				out.write(o.toString());
			}
			popScope(Scope.ANNOTATION_ARRAY_VALUE);
			out.write("\n");
			indent();
			out.write("}");
		} else {
			out.write(value.toString());
		}
		return this;
	}

	/**
	 * Emits a name like {@code java.lang.String} or
	 * {@code java.util.List<java.lang.String>}, compressing it with imports if
	 * possible. Type compression will only be enabled if
	 * {@link #isCompressingTypes} is true.
	 */
	private JavaWriter emitCompressedType(final String type) throws IOException {
		if (isCompressingTypes) {
			out.write(compressType(type));
		} else {
			out.write(type);
		}
		return this;
	}

	public JavaWriter emitEmptyLine() throws IOException {
		out.write("\n");
		return this;
	}

	public JavaWriter emitEnumValue(final String name) throws IOException {
		indent();
		out.write(name);
		out.write(",\n");
		return this;
	}

	/** Emit a list of enum values followed by a semi-colon ({@code ;}). */
	public JavaWriter emitEnumValues(final Iterable<String> names)
			throws IOException {
		final Iterator<String> iterator = names.iterator();

		while (iterator.hasNext()) {
			final String name = iterator.next();
			if (iterator.hasNext()) {
				emitEnumValue(name);
			} else {
				emitLastEnumValue(name);
			}
		}

		return this;
	}

	/** Emits a field declaration. */
	public JavaWriter emitField(final String type, final String name)
			throws IOException {
		return emitField(type, name, EnumSet.noneOf(Modifier.class), null);
	}

	/** Emits a field declaration. */
	public JavaWriter emitField(final String type, final String name,
			final Set<Modifier> modifiers) throws IOException {
		return emitField(type, name, modifiers, null);
	}

	public JavaWriter emitField(final String type, final String name,
			final Set<Modifier> modifiers, final String initialValue)
			throws IOException {
		indent();
		emitModifiers(modifiers);
		emitCompressedType(type);
		out.write(" ");
		out.write(name);

		if (initialValue != null) {
			out.write(" = ");
			out.write(initialValue);
		}
		out.write(";\n");
		return this;
	}

	/**
	 * Emit an import for each {@code type} provided. For the duration of the
	 * file, all references to these classes will be automatically shortened.
	 */
	public JavaWriter emitImports(final Class<?>... types) throws IOException {
		final List<String> classNames = new ArrayList<String>(types.length);
		for (final Class<?> classToImport : types) {
			classNames.add(classToImport.getName());
		}
		return emitImports(classNames);
	}

	/**
	 * Emit an import for each {@code type} in the provided {@code Collection}.
	 * For the duration of the file, all references to these classes will be
	 * automatically shortened.
	 */
	public JavaWriter emitImports(final Collection<String> types)
			throws IOException {
		for (final String type : new TreeSet<String>(types)) {
			final Matcher matcher = TYPE_PATTERN.matcher(type);
			if (!matcher.matches()) {
				throw new IllegalArgumentException(type);
			}
			if (importedTypes.put(type, matcher.group(1)) != null) {
				throw new IllegalArgumentException(type);
			}
			out.write("import ");
			out.write(type);
			out.write(";\n");
		}
		return this;
	}

	/**
	 * Emit an import for each {@code type} provided. For the duration of the
	 * file, all references to these classes will be automatically shortened.
	 */
	public JavaWriter emitImports(final String... types) throws IOException {
		return emitImports(Arrays.asList(types));
	}

	/** Emits some Javadoc comments with line separated by {@code \n}. */
	public JavaWriter emitJavadoc(final String javadoc, final Object... params)
			throws IOException {
		final String formatted = String.format(javadoc, params);

		indent();
		out.write("/**\n");
		for (final String line : formatted.split("\n")) {
			indent();
			out.write(" *");
			if (!line.isEmpty()) {
				out.write(" ");
				out.write(line);
			}
			out.write("\n");
		}
		indent();
		out.write(" */\n");
		return this;
	}

	private JavaWriter emitLastEnumValue(final String name) throws IOException {
		indent();
		out.write(name);
		out.write(";\n");
		return this;
	}

	/** Emits the modifiers to the writer. */
	private void emitModifiers(Set<Modifier> modifiers) throws IOException {
		// Use an EnumSet to ensure the proper ordering
		if (!(modifiers instanceof EnumSet)) {
			modifiers = EnumSet.copyOf(modifiers);
		}
		for (final Modifier modifier : modifiers) {
			out.append(modifier.toString()).append(' ');
		}
	}

	/** Emit a package declaration and empty line. */
	public JavaWriter emitPackage(final String packageName) throws IOException {
		if (packagePrefix != null) {
			throw new IllegalStateException();
		}
		if (packageName.isEmpty()) {
			packagePrefix = "";
		} else {
			out.write("package ");
			out.write(packageName);
			out.write(";\n\n");
			packagePrefix = packageName + ".";
		}
		return this;
	}

	/** Emits a single line comment. */
	public JavaWriter emitSingleLineComment(final String comment,
			final Object... args) throws IOException {
		indent();
		out.write("// ");
		out.write(String.format(comment, args));
		out.write("\n");
		return this;
	}

	/**
	 * @param pattern
	 *            a code pattern like "int i = %s". Newlines will be further
	 *            indented. Should not contain trailing semicolon.
	 */
	public JavaWriter emitStatement(final String pattern, final Object... args)
			throws IOException {
		checkInMethod();
		final String[] lines = String.format(pattern, args).split("\n", -1);
		indent();
		out.write(lines[0]);
		for (int i = 1; i < lines.length; i++) {
			out.write("\n");
			hangingIndent();
			out.write(lines[i]);
		}
		out.write(";\n");
		return this;
	}

	/**
	 * Emit a static import for each {@code type} in the provided
	 * {@code Collection}. For the duration of the file, all references to these
	 * classes will be automatically shortened.
	 */
	public JavaWriter emitStaticImports(final Collection<String> types)
			throws IOException {
		for (final String type : new TreeSet<String>(types)) {
			final Matcher matcher = TYPE_PATTERN.matcher(type);
			if (!matcher.matches()) {
				throw new IllegalArgumentException(type);
			}
			if (importedTypes.put(type, matcher.group(1)) != null) {
				throw new IllegalArgumentException(type);
			}
			out.write("import static ");
			out.write(type);
			out.write(";\n");
		}
		return this;
	}

	/**
	 * Emit a static import for each {@code type} provided. For the duration of
	 * the file, all references to these classes will be automatically
	 * shortened.
	 */
	public JavaWriter emitStaticImports(final String... types)
			throws IOException {
		return emitStaticImports(Arrays.asList(types));
	}

	/** Completes the current constructor declaration. */
	public JavaWriter endConstructor() throws IOException {
		popScope(Scope.CONSTRUCTOR);
		indent();
		out.write("}\n");
		return this;
	}

	public JavaWriter endControlFlow() throws IOException {
		return endControlFlow(null);
	}

	/**
	 * @param controlFlow
	 *            the optional control flow construct and its code, such as
	 *            "while(foo == 20)". Only used for "do/while" control flows.
	 */
	public JavaWriter endControlFlow(final String controlFlow)
			throws IOException {
		popScope(Scope.CONTROL_FLOW);
		indent();
		if (controlFlow != null) {
			out.write("} ");
			out.write(controlFlow);
			out.write(";\n");
		} else {
			out.write("}\n");
		}
		return this;
	}

	/** Ends the current initializer declaration. */
	public JavaWriter endInitializer() throws IOException {
		popScope(Scope.INITIALIZER);
		indent();
		out.write("}\n");
		return this;
	}

	/** Completes the current method declaration. */
	public JavaWriter endMethod() throws IOException {
		final Scope popped = scopes.pop();
		// support calling a constructor a "method" to support the legacy code
		if ((popped == Scope.NON_ABSTRACT_METHOD)
				|| (popped == Scope.CONSTRUCTOR)) {
			indent();
			out.write("}\n");
		} else if (popped != Scope.ABSTRACT_METHOD) {
			throw new IllegalStateException();
		}
		return this;
	}

	public JavaWriter endSwitch() throws IOException {
		popScope(Scope.SWITCH);
		indent();
		out.write("}\n");
		return this;
	}

	/** Completes the current type declaration. */
	public JavaWriter endType() throws IOException {
		popScope(Scope.TYPE_DECLARATION);
		types.pop();
		indent();
		out.write("}\n");
		return this;
	}

	public String getIndent() {
		return indent;
	}

	private void hangingIndent() throws IOException {
		for (int i = 0, count = scopes.size() + 2; i < count; i++) {
			out.write(indent);
		}
	}

	private void indent() throws IOException {
		for (int i = 0, count = scopes.size(); i < count; i++) {
			out.write(indent);
		}
	}

	/**
	 * Returns true if the imports contain a class with same simple name as
	 * {@code compressed}.
	 * 
	 * @param compressed
	 *            simple name of the type
	 */
	private boolean isAmbiguous(final String compressed) {
		return importedTypes.values().contains(compressed);
	}

	private boolean isClassInPackage(final String name) {
		if (name.startsWith(packagePrefix)) {
			if (name.indexOf('.', packagePrefix.length()) == -1) {
				return true;
			}
			// check to see if the part after the package looks like a class
			if (Character.isUpperCase(name.charAt(packagePrefix.length()))) {
				return true;
			}
		}
		return false;
	}

	public boolean isCompressingTypes() {
		return isCompressingTypes;
	}

	/**
	 * @param controlFlow
	 *            the control flow construct and its code, such as
	 *            "else if (foo == 10)". Shouldn't contain braces or newline
	 *            characters.
	 */
	public JavaWriter nextControlFlow(final String controlFlow)
			throws IOException {
		popScope(Scope.CONTROL_FLOW);
		indent();
		scopes.push(Scope.CONTROL_FLOW);
		out.write("} ");
		out.write(controlFlow);
		out.write(" {\n");
		return this;
	}

	private void popScope(final Scope expected) {
		if (scopes.pop() != expected) {
			throw new IllegalStateException();
		}
	}

	public void setCompressingTypes(final boolean isCompressingTypes) {
		this.isCompressingTypes = isCompressingTypes;
	}

	public void setIndent(final String indent) {
		this.indent = indent;
	}
}
