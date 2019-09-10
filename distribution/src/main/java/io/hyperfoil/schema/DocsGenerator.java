package io.hyperfoil.schema;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;

import io.hyperfoil.api.config.BaseSequenceBuilder;
import io.hyperfoil.api.config.ListBuilder;
import io.hyperfoil.api.config.Locator;
import io.hyperfoil.api.config.MappingListBuilder;
import io.hyperfoil.api.config.PairBuilder;
import io.hyperfoil.api.config.PartialBuilder;
import io.hyperfoil.api.config.ServiceLoadedFactory;
import io.hyperfoil.api.config.StepBuilder;
import io.hyperfoil.api.connection.HttpRequest;
import io.hyperfoil.api.connection.Request;
import io.hyperfoil.api.session.Action;
import io.hyperfoil.core.builders.StepCatalog;
import io.hyperfoil.core.steps.ServiceLoadedBuilderProvider;

public class DocsGenerator extends BaseGenerator {
   private static final Set<Class<?>> BLACKLIST = new HashSet<>(Arrays.asList(
         BaseSequenceBuilder.class, ListBuilder.class, MappingListBuilder.class,
         PairBuilder.class, PairBuilder.OfString.class, PairBuilder.OfDouble.class,
         PartialBuilder.class));

   private static final Pattern END_REGEXP = Pattern.compile("^end(\\p{javaUpperCase}.*|$)");
   private static final String NO_DESCRIPTION = "<font color=\"#606060\">&lt;no description&gt;</font>";
   private static final Docs EMPTY_DOCS = new Docs(null);
   private final List<Path> sourceDirs;
   private final Path output;
   private final Map<String, Docs> steps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
   private final Map<Class<?>, Docs> docs = new HashMap<>();
   private final Map<Docs, Class<?>> reverseTypes = new HashMap<>();
   private final JavaParser parser = new JavaParser();

   public static void main(String[] args) {
      List<Path> sourceDirs = new ArrayList<>();
      for (int i = 0; i < args.length - 1; ++i) {
         sourceDirs.add(Paths.get(args[i]));
      }
      if (args.length > 0) {
         new DocsGenerator(sourceDirs, Paths.get(args[args.length - 1])).run();
      }
   }

   private DocsGenerator(List<Path> sourceDirs, Path output) {
      this.sourceDirs = sourceDirs;
      this.output = output;
   }

   private void run() {
      List<MethodDeclaration> catalogMethods = findUnit(StepCatalog.class).findAll(MethodDeclaration.class);

      for (Method method : StepCatalog.class.getMethods()) {
         MethodDeclaration declaration = findMatching(catalogMethods, method);
         if (declaration == null) {
            continue;
         }
         if (StepBuilder.class.isAssignableFrom(method.getReturnType())) {
            addStep(method.getName(), method.getReturnType(), getJavadocDescription(declaration), false, null);
         } else if (BaseSequenceBuilder.class.isAssignableFrom(method.getReturnType())) {
            addSimpleStep(method, declaration);
         }
      }
      for (Object f : getFactories(StepBuilder.Factory.class)) {
         StepBuilder.Factory factory = (StepBuilder.Factory) f;
         try {
            Class<?> newBuilder = factory.getClass().getMethod("newBuilder", Locator.class, String.class).getReturnType();
            ClassOrInterfaceDeclaration cd = findClass(factory.getClass());
            if (cd != null) {
               String inlineParamDocs = findInlineParamDocs(cd);
               addStep(factory.name(), newBuilder, null, factory.acceptsParam(), inlineParamDocs);
            }
         } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
         }
      }
      for (Map.Entry<Class<?>, Docs> entry : docs.entrySet()) {
         reverseTypes.put(entry.getValue(), entry.getKey());
      }

      File outputDir = output.toFile();
      if (outputDir.exists()) {
         if (!outputDir.isDirectory()) {
            System.err.println("Output paramater " + output.toString() + " must be a folder");
         }
      } else {
         outputDir.mkdirs();
      }
      Path indexPath = output.resolve("index.md");
      try (PrintStream out = new PrintStream(new FileOutputStream(indexPath.toFile()))) {
         out.println("# Hyperfoil reference\n");
         out.println("\n\n## Steps");
         for (Map.Entry<String, Docs> step : steps.entrySet()) {
            printDocs("step", step.getKey(), step.getValue(), out);
         }
         out.println("\n\n## Actions");
         for (Map.Entry<String, List<Docs>> action : docs.get(Action.BuilderFactory.class).params.entrySet()) {
            printDocs("action", action.getKey(), action.getValue().iterator().next(), out);
         }
         out.println("\n\n## Processors");
         for (Map.Entry<String, List<Docs>> action : docs.get(Request.ProcessorBuilderFactory.class).params.entrySet()) {
            printDocs("processor", action.getKey(), action.getValue().iterator().next(), out);
         }
      } catch (IOException e) {
         System.err.printf("Cannot write index file %s: %s%n", indexPath, e);
      }
      for (Map.Entry<String, Docs> step : steps.entrySet()) {
         Path filePath = output.resolve("step_" + step.getKey() + ".md");
         try (PrintStream out = new PrintStream(new FileOutputStream(filePath.toFile()))) {
            out.printf("# %s%n%n", step.getKey());
            printDocs(step.getValue(), out);
         } catch (FileNotFoundException e) {
            System.err.printf("Cannot write file %s: %s%n", filePath, e);
         }
      }
      printRootType("action", Action.BuilderFactory.class);
      printRootType("processor", Request.ProcessorBuilderFactory.class);
   }

   private void printRootType(String type, Class<? extends ServiceLoadedFactory> factoryClass) {
      for (Map.Entry<String, List<Docs>> entry : docs.get(factoryClass).params.entrySet()) {
         Path filePath = output.resolve(type + "_" + entry.getKey() + ".md");
         try (PrintStream out = new PrintStream(new FileOutputStream(filePath.toFile()))) {
            out.printf("# %s%n%n", entry.getKey());
            printDocs(entry.getValue().iterator().next(), out);
         } catch (FileNotFoundException e) {
            System.err.printf("Cannot write file %s: %s%n", filePath, e);
         }
      }
   }

   private void printDocs(String type, String name, Docs docs, PrintStream out) {
      String description = docs.ownerDescription;
      if (description == null) {
         out.printf("* [%s](./%s_%s.html)%n", name, type, name);
      } else {
         int endOfLine = description.indexOf('\n');
         if (endOfLine >= 0) {
            description = description.substring(0, endOfLine);
         }
         out.printf("* [%s](./%s_%s.html): %s%n", name, type, name, description);
      }
   }

   private void printDocs(Docs docs, PrintStream out) {
      if (docs.typeDescription != null) {
         out.println(docs.typeDescription);
      }
      if (docs.inlineParam != null) {
         out.println();
         out.println("| Inline definition |\n| -------- |");
         out.printf("| %s |%n", docs.inlineParam);
         out.println();
      }
      if (!docs.params.isEmpty()) {
         List<Tuple> children = new ArrayList<>();
         List<Tuple> processed = new ArrayList<>();
         Set<Docs> found = new HashSet<>();
         for (Map.Entry<String, List<Docs>> param : docs.params.entrySet()) {
            for (Docs d : param.getValue()) {
               if (d.link == null && !d.params.isEmpty() && found.add(d)) {
                  processed.add(new Tuple(param.getKey(), d));
               }
            }
         }
         while (!processed.isEmpty()) {
            children.addAll(processed);
            List<Tuple> newChildren = new ArrayList<>();
            for (Tuple t : children) {
               for (Map.Entry<String, List<Docs>> param : t.docs.params.entrySet()) {
                  for (Docs d : param.getValue()) {
                     if (d.link == null && !d.params.isEmpty() && found.add(d)) {
                        newChildren.add(new Tuple(t.name + "." + param.getKey(), d));
                     }
                  }
               }
            }
            processed = newChildren;
         }
         Map<Docs, String> reverseLookup = new HashMap<>();
         for (Tuple t : children) {
            reverseLookup.put(t.docs, t.name);
         }

         out.println();
         out.println("| Property | Description |\n| ------- | -------- |");
         for (Map.Entry<String, List<Docs>> param : docs.params.entrySet()) {
            printDocs(param.getKey(), param.getValue(), out, reverseLookup);
         }
         out.println();

         Collections.sort(children, Comparator.comparing(t -> t.name, String.CASE_INSENSITIVE_ORDER));
         for (Tuple t : children) {
            out.printf("### <a id=\"%s\"></a>%s%n%n", reverseLookup.get(t.docs), t.name);
            if (t.docs.typeDescription != null) {
               out.println(t.docs.typeDescription);
               out.println();
            }
            out.println("| Property | Description |\n| ------- | -------- |");
            for (Map.Entry<String, List<Docs>> param : t.docs.params.entrySet()) {
               printDocs(param.getKey(), param.getValue(), out, reverseLookup);
            }
            out.println();
         }
      }
   }

   private void printDocs(String name, List<Docs> options, PrintStream out, Map<Docs, String> reverseLookup) {
      int printed = 0;
      for (Docs d : options) {
         if (d.ownerDescription == null && d.params.isEmpty()) {
            continue;
         }
         if (d.link != null) {
            out.printf("| [%s](%s) ", name, d.link);
         } else if (d.params.isEmpty()) {
            out.printf("| %s ", name);
         } else {
            out.printf("| [%s](#%s) ", name, reverseLookup.get(d));
         }
         if (printed > 0) {
            out.print("(alternative)");
         }
         out.printf("| %s |%n", d.ownerDescription == null ? NO_DESCRIPTION : d.ownerDescription);
         ++printed;
      }
      if (printed == 0) {
         out.printf("| %s | %s |%n", name, NO_DESCRIPTION);
      }
   }

   private String findInlineParamDocs(ClassOrInterfaceDeclaration cd) {
      return cd.findFirst(MethodDeclaration.class, md -> matches(md, "newBuilder", Locator.class, String.class))
            .map(md -> getJavadocParams(md.getJavadoc()).get("param")).orElse(null);
   }

   private MethodDeclaration findMatching(List<MethodDeclaration> methods, Method method) {
      METHODS: for (MethodDeclaration m : methods) {
         int parameterCount = m.getParameters().size();
         if (m.getName().asString().equals(method.getName()) && parameterCount == method.getParameterCount()) {
            for (int i = 0; i < parameterCount; ++i) {
               if (!matches(m.getParameter(i).getType(), method.getParameters()[i].getType())) {
                  continue METHODS;
               }
            }
            return m;
         }
      }
      return null;
   }

   private boolean matches(Type type, Class<?> clazz) {
      if (type instanceof PrimitiveType) {
//         System.err.printf("COMP %s and %s", ((PrimitiveType) type).getType().asString(), clazz.getName());
         return ((PrimitiveType) type).getType().asString().equals(clazz.getName());
      } else if (type instanceof ClassOrInterfaceType) {
         ClassOrInterfaceType classType = (ClassOrInterfaceType) type;
         String fqName = fqName(classType);
//         System.err.printf("COMP %s and %s", fqName, clazz.getName());
         return clazz.getName().endsWith(fqName);
      } else {
         System.err.printf("TYPE %s%n", type);
      }
      return false;
   }

   private boolean matches(MethodDeclaration declaration, String name, Class<?>... parameters) {
      if (!declaration.getName().asString().equals(name) || declaration.getParameters().size() != parameters.length) {
         return false;
      }
      for (int i = 0; i < parameters.length; ++i) {
         if (!matches(declaration.getParameter(i).getType(), parameters[i])) {
            return false;
         }
      }
      return true;
   }


   private String fqName(ClassOrInterfaceType type) {
      return type.getScope().map(s -> fqName(s) + ".").orElse("") + type.getNameAsString();
   }

   private CompilationUnit findUnit(Class<?> clazz) {
      while (clazz.getEnclosingClass() != null) {
         clazz = clazz.getEnclosingClass();
      }
      String src = clazz.getName().replaceAll("\\.", File.separator) + ".java";
      File file = sourceDirs.stream().map(path -> path.resolve(src).toFile())
            .filter(f -> f.exists() && f.isFile()).findFirst().orElse(null);
      if (file != null) {
         try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (result.isSuccessful()) {
               return result.getResult().orElseThrow(IllegalStateException::new);
            } else {
               System.err.printf("Cannot parse file %s:%n", file);
               for (Problem p : result.getProblems()) {
                  System.err.println(p.getVerboseMessage());
               }
            }
         } catch (FileNotFoundException e) {
            System.err.printf("Cannot read file %s: %s%n", file, e.getMessage());
         }
      }
      if (!clazz.getName().startsWith("java.")) {
         System.err.printf("Cannot find source code for %s%n", clazz);
      }
      return null;
   }

   private ClassOrInterfaceDeclaration findClass(Class<?> builder) {
      Node node = findClassOrEnum(builder, ClassOrInterfaceDeclaration.class);
      if (node == null) return null;
      return (ClassOrInterfaceDeclaration) node;
   }

   private EnumDeclaration findEnum(Class<?> builder) {
      Node node = findClassOrEnum(builder, EnumDeclaration.class);
      if (node == null) return null;
      return (EnumDeclaration) node;
   }

   private <T extends Node & NodeWithSimpleName> Node findClassOrEnum(Class<?> builder, Class<T> type) {
      if (BLACKLIST.contains(builder)) {
         return null;
      }
      Node node = findUnit(builder);
      if (node == null) {
         return null;
      }
      Stack<Class<?>> classes = new Stack<>();
      Class<?> clazz = builder;
      while (clazz != null) {
         classes.push(clazz);
         clazz = clazz.getEnclosingClass();
      }
      while (!classes.isEmpty()) {
         String simpleName = classes.pop().getSimpleName();
         if (classes.isEmpty()) {
            node = node.findFirst(type, cd -> cd.getNameAsString().equals(simpleName)).orElse(null);
         } else {
            node = node.findFirst(ClassOrInterfaceDeclaration.class, cd -> cd.getNameAsString().equals(simpleName)).orElse(null);
         }
         if (node == null) {
            System.err.printf("Cannot describe builder %s%n", builder);
            return null;
         }
      }
      return node;
   }

   private void addSimpleStep(Method method, MethodDeclaration md) {
      String description = getJavadocDescription(md);
      if (method.getParameterCount() == 0) {
         steps.putIfAbsent(method.getName(), new Docs(description));
      } else if (method.getParameterCount() == 1) {
         Docs step = steps.get(method.getName());
         if (step == null) {
            step = new Docs(description);
            steps.put(method.getName(), step);
         }
         Map<String, String> params = getJavadocParams(md.getJavadoc());
         String paramName = md.getParameter(0).getNameAsString();
         step.inlineParam = params.get(paramName);
      }
   }

   private String getJavadocDescription(NodeWithJavadoc<?> declaration) {
      return declaration == null ? null : declaration.getJavadoc()
            .map(javadoc -> trimEmptyLines(javadoc.getDescription().toText()))
            .map(text -> text.contains("<ul>") ? "{::nomarkdown}" + text + "{:/}" : text)
            .orElse(null);
   }

   private String trimEmptyLines(String description) {
      String[] lines = description.split("\n");
      int firstLine = 0, lastLine = lines.length - 1;
      for (; firstLine < lines.length; ++firstLine) {
         if (!lines[firstLine].trim().isEmpty()) break;
      }
      for (; lastLine >= firstLine; --lastLine) {
         if (!lines[lastLine].trim().isEmpty()) break;
      }
      StringBuilder sb = new StringBuilder();
      for (int i = firstLine; i <= lastLine; ++i) {
         if (lines[i].trim().isEmpty()) {
            sb.append("<br>");
         }
         sb.append(lines[i]).append(" ");
      }
      if (sb.length() == 0) {
         return null;
      }
      return sb.toString();
   }

   private Map<String, String> getJavadocParams(Optional<Javadoc> maybeJavadoc) {
      return maybeJavadoc
            .map(javadoc -> javadoc.getBlockTags().stream()
                  .filter(tag -> tag.getType() == JavadocBlockTag.Type.PARAM)
                  .collect(Collectors.toMap(tag -> tag.getName().orElse("<unknown>"), tag -> tag.getContent().toText())))
            .orElse(Collections.emptyMap());
   }

   private void addStep(String name, Class<?> builder, String description, boolean inline, String inlineDocs) {
      Docs step = steps.get(name);
      if (step == null) {
         step = describeBuilder(builder);
         if (description == null) {
            step.ownerDescription = firstLine(step.typeDescription);
         } else {
            step.ownerDescription = description;
         }
         if (step == null) {
            return;
         }
         steps.put(name, step);
      } else if (step.params.isEmpty()) {
         // The step could have been created from inline-param version in StepCatalog
         Docs docs = describeBuilder(builder);
         step.typeDescription = description != null ? description : docs.typeDescription;
         step.params.putAll(docs.params);
         if (step.ownerDescription == null) {
            step.ownerDescription = firstLine(step.typeDescription);
         }
      } else if (step.ownerDescription == null && description != null) {
         step.ownerDescription = description;
      }
      if (step.inlineParam == null && inline) {
         step.inlineParam = inlineDocs;
      }
   }

   private String firstLine(String text) {
      return text == null ? null : text.replaceFirst("(\n|<br>).*", "");
   }

   private Docs describeBuilder(Class<?> builder) {
      if (docs.containsKey(builder)) {
         return docs.get(builder);
      }
      ClassOrInterfaceDeclaration cd = findClass(builder);
      if (cd == null) {
         return null;
      }
      List<MethodDeclaration> methods = findAllMethods(builder);
      Docs docs = new Docs(null);
      docs.typeDescription = getJavadocDescription(cd);
      this.docs.put(builder, docs);
      if (BaseSequenceBuilder.class.isAssignableFrom(builder)) {
         return docs;
      }
      for (Method m : builder.getMethods()) {
         if (Modifier.isStatic(m.getModifiers()) || m.isDefault() || m.isSynthetic() || m.isBridge()) {
            continue;
         } else if (END_REGEXP.matcher(m.getName()).matches()) {
            continue; // do not go up
         } else if (PairBuilder.class.isAssignableFrom(builder) && m.getName().equals("accept") && m.getParameterCount() == 2) {
            continue;
         } else if (PartialBuilder.class.isAssignableFrom(builder) && m.getName().equals("withKey") && m.getParameterCount() == 1) {
            continue;
         } else if (ListBuilder.class.isAssignableFrom(builder) && m.getName().equals("nextItem") && m.getParameterCount() == 1) {
            continue;
         } else if (MappingListBuilder.class.isAssignableFrom(builder) && m.getName().equals("addItem") && m.getParameterCount() == 0) {
            continue;
         } else if (m.getName().equals("copy") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Locator.class) {
            continue;
         }
         Docs param = describeMethod(builder, m, findMatching(methods, m));
         if (param != null) {
            docs.addParam(m.getName(), param);
         }
      }
      return docs;
   }

   private List<MethodDeclaration> findAllMethods(Class<?> clazz) {
      List<MethodDeclaration> declarations = new ArrayList<>();
      while (clazz != null) {
         ClassOrInterfaceDeclaration cd = findClass(clazz);
         if (cd != null) {
            declarations.addAll(cd.findAll(MethodDeclaration.class));
         }
         clazz = clazz.getSuperclass();
      }
      return declarations;
   }

   private Docs describeMethod(Class<?> builder, Method m, MethodDeclaration declaration) {
      StringBuilder description = declaration == null ? new StringBuilder() : declaration.getJavadoc()
            .map(javadoc -> new StringBuilder(trimEmptyLines(javadoc.getDescription().toText()))).orElse(new StringBuilder());

      // Return early to not recurse into self
      if (m.getReturnType().isAssignableFrom(builder)) {
         if (m.getParameterCount() == 0) {
            if (description != null) {
               description.append("<br>Note: property does not have any value");
            }
         } else if (m.getParameterCount() == 1) {
            Class<?> singleParam = m.getParameters()[0].getType();
            if (singleParam.isEnum()) {
               EnumDeclaration cd = findEnum(singleParam);
               if (cd != null) {
                  List<EnumConstantDeclaration> constants = cd.findAll(EnumConstantDeclaration.class);
                  if (constants != null) {
                     description.append("<br>Options:{::nomarkdown}<ul>");
                     for (EnumConstantDeclaration c : constants) {
                        description.append("<li><code>").append(c.getNameAsString()).append("</code>");
                        String optionDescription = getJavadocDescription(c);
                        if (optionDescription != null) {
                           description.append(": {:/}").append(optionDescription).append("{::nomarkdown}");
                        }
                        description.append("</li>");
                     }
                     description.append("</ul>{:/}");
                  }
               }
            }
         }
         return new Docs(description.length() == 0 ? null : description.toString());
      }

      Docs param = new Docs(description.length() == 0 ? null : description.toString());

      if (PairBuilder.class.isAssignableFrom(m.getReturnType())) {
         Docs inner = describeBuilder(m.getReturnType());
         ClassOrInterfaceDeclaration cd = findClass(m.getReturnType());
         if (cd != null) {
            inner.ownerDescription = cd.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals("accept") && md.getParameters().size() == 2)
                  .map(this::getJavadocDescription).orElse(null);
         }
         param.addParam("&lt;any&gt;", inner);
      }
      if (PartialBuilder.class.isAssignableFrom(m.getReturnType())) {
         try {
            Class<?> innerBuilder = m.getReturnType().getMethod("withKey", String.class).getReturnType();
            Docs inner = describeBuilder(innerBuilder);
            ClassOrInterfaceDeclaration cd = findClass(m.getReturnType());
            if (cd != null) {
               inner.ownerDescription = cd.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals("withKey") && md.getParameters().size() == 1)
                     .map(this::getJavadocDescription).orElse(null);
            }
            param.addParam("&lt;any&gt;", inner);
         } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
         }
      }

      if (BaseSequenceBuilder.class.isAssignableFrom(m.getReturnType())) {
         param.addParam("&lt;list of steps&gt;", EMPTY_DOCS);
      }
      if (ListBuilder.class.isAssignableFrom(m.getReturnType())) {
         Docs inner = describeBuilder(m.getReturnType());
         param.addParam("&lt;list of strings&gt;", new Docs(inner == null ? null : inner.typeDescription));
      }
      if (MappingListBuilder.class.isAssignableFrom(m.getReturnType())) {
         try {
            Docs inner = describeBuilder(m.getReturnType().getMethod("addItem").getReturnType());
            ClassOrInterfaceDeclaration cd = findClass(m.getReturnType());
            if (cd != null) {
               inner.ownerDescription = cd.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals("addItem") && md.getParameters().size() == 0)
                     .map(this::getJavadocDescription).orElse(null);
            }
            param.addParam("&lt;list of mappings&gt;", inner);
         } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
         }
      }
      if (ServiceLoadedBuilderProvider.class.isAssignableFrom(m.getReturnType())) {
         ParameterizedType returnType = (ParameterizedType) m.getAnnotatedReturnType().getType();
         Class<? extends ServiceLoadedFactory<?>> bfClass = getBuilderFactoryClass(returnType.getActualTypeArguments()[1]);
         if (bfClass == Action.BuilderFactory.class) {
            param.link = "index.html#actions";
         } else if (bfClass == Request.ProcessorBuilderFactory.class || bfClass == HttpRequest.ProcessorBuilderFactory.class) {
            param.link = "index.html#processors";
         }
         param.addParams(getServiceLoadedImplementations(bfClass).params);
      }
      if (m.getReturnType().getName().endsWith("Builder")) {
         Docs inner = describeBuilder(m.getReturnType());
         if (inner != null) {
            param.typeDescription = inner.typeDescription;
            param.addParams(inner.params);
         }
      }
      if (param.params.isEmpty()) {
         return null;
      } else {
         return param;
      }
   }

   private Docs getServiceLoadedImplementations(Class<? extends ServiceLoadedFactory<?>> factoryClass) {
      Docs implementations = docs.get(factoryClass);
      if (implementations != null) {
         return implementations;
      }
      implementations = new Docs(null);
      docs.put(factoryClass, implementations);
      ClassOrInterfaceDeclaration fd = findClass(factoryClass);
      implementations.typeDescription = getJavadocDescription(fd);
      for (ServiceLoadedFactory f : getFactories(factoryClass)) {
         try {
            Class<?> serviceLoadedBuilder = f.getClass().getMethod("newBuilder", Locator.class, String.class).getReturnType();
            Docs docs = describeBuilder(serviceLoadedBuilder);
            docs.ownerDescription = docs.typeDescription;
            ClassOrInterfaceDeclaration cd = findClass(f.getClass());
            if (cd != null && f.acceptsParam()) {
               docs.inlineParam = findInlineParamDocs(cd);
            }
            implementations.addParam(f.name(), docs);
         } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
         }
      }
      return implementations;
   }

   private static class Docs {
      String ownerDescription;
      String typeDescription;
      String inlineParam;
      Map<String, List<Docs>> params = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      String link;

      private Docs(String ownerDescription) {
         this.ownerDescription = ownerDescription;
         this.typeDescription = ownerDescription;
      }

      public void addParam(String name, Docs docs) {
         List<Docs> options = params.get(name);
         if (options == null) {
            options = new ArrayList<>();
            params.put(name, options);
         }
         options.add(docs);
      }

      public void addParams(Map<String, List<Docs>> params) {
         for (Map.Entry<String, List<Docs>> param : params.entrySet()) {
            for (Docs d : param.getValue()) {
               addParam(param.getKey(), d);
            }
         }
      }
   }

   private static class Tuple {
      final String name;
      final Docs docs;

      private Tuple(String name, Docs docs) {
         this.name = name;
         this.docs = docs;
      }
   }
}