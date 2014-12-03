/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ForStatementTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.LiteralTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.Tree.Kind;
import org.sonar.plugins.java.api.tree.UnaryExpressionTree;
import org.sonar.plugins.java.api.tree.VariableTree;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import java.util.List;

public abstract class AbstractForLoopRule extends SubscriptionBaseVisitor {

  @Override
  public List<Kind> nodesToVisit() {
    return ImmutableList.of(Tree.Kind.FOR_STATEMENT);
  }

  @Override
  public void visitNode(Tree tree) {
    ForStatementTree forStatement = (ForStatementTree) tree;
    visitForStatement(forStatement);
  }

  public abstract void visitForStatement(ForStatementTree forStatement);

  @CheckForNull
  public static Integer intLiteralValue(ExpressionTree expression) {
    if (expression.is(Tree.Kind.INT_LITERAL)) {
      return intLiteralValue((LiteralTree) expression);
    }
    if (expression.is(Tree.Kind.UNARY_MINUS, Tree.Kind.UNARY_PLUS)) {
      UnaryExpressionTree unaryExp = (UnaryExpressionTree) expression;
      Integer subExpressionIntValue = intLiteralValue(unaryExp.expression());
      return expression.is(Tree.Kind.UNARY_MINUS) ? minus(subExpressionIntValue) : subExpressionIntValue;
    }
    return null;
  }

  @CheckForNull
  private static Integer intLiteralValue(LiteralTree literal) {
    String literalValue = literal.value();
    if (literalValue.startsWith("0x") || literalValue.startsWith("0b")) {
      return null;
    }
    return Integer.valueOf(literalValue);
  }

  @CheckForNull
  private static Integer minus(@Nullable Integer nullableInteger) {
    return nullableInteger == null ? null : -nullableInteger;
  }

  protected static boolean isSameIdentifier(IdentifierTree identifier, ExpressionTree expression) {
    if (expression.is(Tree.Kind.IDENTIFIER)) {
      IdentifierTree other = (IdentifierTree) expression;
      return other.name().equals(identifier.name());
    }
    return false;
  }

  protected static class IntVariable {

    private final IdentifierTree identifier;

    public IntVariable(IdentifierTree identifier) {
      this.identifier = identifier;
    }

    public boolean hasSameIdentifier(ExpressionTree expression) {
      return isSameIdentifier(identifier, expression);
    }

    public IdentifierTree identifier() {
      return identifier;
    }
  }

  protected static class ForLoopInitializer extends IntVariable {

    private final Integer value;

    public ForLoopInitializer(IdentifierTree identifier, Integer value) {
      super(identifier);
      this.value = value;
    }

    public Integer value() {
      return value;
    }

    public static Iterable<ForLoopInitializer> list(ForStatementTree forStatement) {
      List<ForLoopInitializer> list = Lists.newArrayList();
      for (StatementTree statement : forStatement.initializer()) {
        if (statement.is(Tree.Kind.VARIABLE)) {
          VariableTree variable = (VariableTree) statement;
          ExpressionTree initializer = variable.initializer();
          Integer value = initializer == null ? null : intLiteralValue(initializer);
          list.add(new ForLoopInitializer(variable.simpleName(), value));
        }
        if (statement.is(Tree.Kind.EXPRESSION_STATEMENT)) {
          ExpressionTree expression = ((ExpressionStatementTree) statement).expression();
          ForLoopInitializer initializer = assignmentInitializer(expression);
          if (initializer != null) {
            list.add(initializer);
          }
        }
      }
      return list;
    }

    private static ForLoopInitializer assignmentInitializer(ExpressionTree expression) {
      if (expression.is(Tree.Kind.ASSIGNMENT)) {
        AssignmentExpressionTree assignment = (AssignmentExpressionTree) expression;
        ExpressionTree variable = assignment.variable();
        if (variable.is(Tree.Kind.IDENTIFIER)) {
          return new ForLoopInitializer((IdentifierTree) variable, intLiteralValue(assignment.expression()));
        }
      }
      return null;
    }

  }

  protected static class ForLoopIncrement extends IntVariable {

    private final Integer value;

    public ForLoopIncrement(IdentifierTree identifier, Integer value) {
      super(identifier);
      this.value = value;
    }

    public Integer value() {
      return value;
    }

    @CheckForNull
    public static ForLoopIncrement findInUpdates(ForStatementTree forStatement) {
      ForLoopIncrement result = null;
      List<StatementTree> updates = forStatement.update();
      if (updates.size() == 1) {
        ExpressionStatementTree statement = (ExpressionStatementTree) updates.get(0);
        ExpressionTree expression = statement.expression();
        if (expression.is(Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.PREFIX_INCREMENT)) {
          UnaryExpressionTree unaryExp = (UnaryExpressionTree) expression;
          result = increment(unaryExp.expression(), 1);
        } else if (expression.is(Tree.Kind.POSTFIX_DECREMENT, Tree.Kind.PREFIX_DECREMENT)) {
          UnaryExpressionTree unaryExp = (UnaryExpressionTree) expression;
          result = increment(unaryExp.expression(), -1);
        } else if (expression.is(Tree.Kind.PLUS_ASSIGNMENT)) {
          AssignmentExpressionTree assignmentExp = (AssignmentExpressionTree) expression;
          result = increment(assignmentExp.variable(), intLiteralValue(assignmentExp.expression()));
        } else if (expression.is(Tree.Kind.MINUS_ASSIGNMENT)) {
          AssignmentExpressionTree assignmentExp = (AssignmentExpressionTree) expression;
          result = increment(assignmentExp.variable(), minus(intLiteralValue(assignmentExp.expression())));
        } else if (expression.is(Tree.Kind.ASSIGNMENT)) {
          AssignmentExpressionTree assignment = (AssignmentExpressionTree) expression;
          result = assignmentIncrement(assignment);
        }
      }
      return result;
    }

    @CheckForNull
    private static ForLoopIncrement increment(ExpressionTree expression, Integer value) {
      if (expression.is(Tree.Kind.IDENTIFIER)) {
        return new ForLoopIncrement((IdentifierTree) expression, value);
      }
      return null;
    }

    private static ForLoopIncrement assignmentIncrement(AssignmentExpressionTree assignmentExpression) {
      ExpressionTree expression = assignmentExpression.expression();
      ExpressionTree variable = assignmentExpression.variable();
      if (variable.is(Tree.Kind.IDENTIFIER)) {
        if (expression.is(Tree.Kind.PLUS, Tree.Kind.MINUS)) {
          BinaryExpressionTree binaryExp = (BinaryExpressionTree) expression;
          Integer increment = intLiteralValue(binaryExp.rightOperand());
          if (increment != null && isSameIdentifier((IdentifierTree) variable, binaryExp.leftOperand())) {
            increment = expression.is(Tree.Kind.MINUS) ? minus(increment) : increment;
            return increment(variable, increment);
          }
          return new ForLoopIncrement((IdentifierTree) variable, null);
        }
      }
      return null;
    }

  }
}