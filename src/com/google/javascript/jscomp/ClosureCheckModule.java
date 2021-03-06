/*
 * Copyright 2015 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import com.google.common.base.Preconditions;
import com.google.javascript.jscomp.NodeTraversal.Callback;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * Checks that goog.module() is used correctly.
 *
 * Note that this file only does checks that can be done per-file. Whole program
 * checks happen during goog.module rewriting, in {@link ClosureRewriteModule}.
 */
public final class ClosureCheckModule implements Callback, HotSwapCompilerPass {
  static final DiagnosticType MULTIPLE_MODULES_IN_FILE =
      DiagnosticType.error(
          "JSC_MULTIPLE_MODULES_IN_FILE",
          "There should only be a single goog.module() statement per file.");

  static final DiagnosticType MODULE_AND_PROVIDES =
      DiagnosticType.error(
          "JSC_MODULE_AND_PROVIDES",
          "A file using goog.module() may not also use goog.provide() statements.");

  static final DiagnosticType GOOG_MODULE_REFERENCES_THIS = DiagnosticType.error(
      "JSC_GOOG_MODULE_REFERENCES_THIS",
      "The body of a goog.module cannot reference 'this'.");

  static final DiagnosticType GOOG_MODULE_USES_THROW = DiagnosticType.error(
      "JSC_GOOG_MODULE_USES_THROW",
      "The body of a goog.module cannot use 'throw'.");

  static final DiagnosticType REFERENCE_TO_MODULE_GLOBAL_NAME =
      DiagnosticType.error(
          "JSC_REFERENCE_TO_MODULE_GLOBAL_NAME",
          "References to the global name of a module are not allowed. Perhaps you meant exports?");

  static final DiagnosticType REQUIRE_NOT_AT_TOP_LEVEL =
      DiagnosticType.error(
          "JSC_REQUIRE_NOT_AT_TOP_LEVEL",
          "goog.require() must be called at file scope.");

  static final DiagnosticType ONE_REQUIRE_PER_DECLARATION =
      DiagnosticType.error(
          "JSC_ONE_REQUIRE_PER_DECLARATION",
          "There may only be one goog.require() per var/let/const declaration.");

  // Temporary error, until b/27675195 is fixed.
  static final DiagnosticType SHORTHAND_OBJLIT_NOT_ALLOWED =
      DiagnosticType.error(
          "JSC_SHORTHAND_OBJLIT_NOT_ALLOWED",
          "Shorthand object literal keys are not allowed in the exports object.");

  private final AbstractCompiler compiler;

  private String currentModuleName = null;

  public ClosureCheckModule(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverseEs6(compiler, root, this);
    // TODO(blickly): Move this behavior into ClosureRewriteModule
    (new ClosureCheckModuleImports(compiler)).process(externs, root);
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    NodeTraversal.traverseEs6(compiler, scriptRoot, this);
  }

  @Override
  public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
    if (n.isScript()) {
      return NodeUtil.isModuleFile(n);
    }
    return true;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    switch (n.getType()) {
      case Token.CALL:
        Node callee = n.getFirstChild();
        if (callee.matchesQualifiedName("goog.module")) {
          if (currentModuleName == null) {
            Node moduleNameNode = n.getSecondChild();
            currentModuleName = moduleNameNode.isString() ? moduleNameNode.getString() : "";
          } else {
            t.report(n, MULTIPLE_MODULES_IN_FILE);
          }
        } else if (callee.matchesQualifiedName("goog.provide")) {
          t.report(n, MODULE_AND_PROVIDES);
        } else if (callee.matchesQualifiedName("goog.require")) {
          checkRequireCall(t, n, parent);
        }
        break;
      case Token.ASSIGN:
        if (n.getFirstChild().matchesQualifiedName("exports")) {
          checkExportsAssignment(t, n);
        }
        break;
      case Token.THIS:
        if (t.inGlobalHoistScope()) {
          t.report(n, GOOG_MODULE_REFERENCES_THIS);
        }
        break;
      case Token.THROW:
        if (t.inGlobalHoistScope()) {
          t.report(n, GOOG_MODULE_USES_THROW);
        }
        break;
      case Token.GETPROP:
        if (currentModuleName != null && n.matchesQualifiedName(currentModuleName)) {
          t.report(n, REFERENCE_TO_MODULE_GLOBAL_NAME);
        }
        break;
      case Token.SCRIPT:
        currentModuleName = null;
        break;
    }
  }

  private void checkRequireCall(NodeTraversal t, Node callNode, Node parent) {
    Preconditions.checkState(callNode.isCall());
    switch (parent.getType()) {
      case Token.EXPR_RESULT:
        return;
      case Token.GETPROP:
        if (parent.getParent().isName()) {
          checkRequireCall(t, callNode, parent.getParent());
          return;
        }
        break;
      case Token.NAME:
      case Token.OBJECT_PATTERN: {
        Node declaration = parent.getParent();
        if (declaration.getChildCount() != 1) {
          t.report(declaration, ONE_REQUIRE_PER_DECLARATION);
        }
        return;
      }
    }
    t.report(callNode, REQUIRE_NOT_AT_TOP_LEVEL);
  }

  private void checkExportsAssignment(NodeTraversal t, Node assign) {
    Node rhs = assign.getLastChild();
    if (!rhs.isObjectLit()) {
      return;
    }
    for (Node child = rhs.getFirstChild(); child != null; child = child.getNext()) {
      if (child.isStringKey() && !child.hasChildren()) {
        t.report(child, SHORTHAND_OBJLIT_NOT_ALLOWED);
      }
    }
  }
}
