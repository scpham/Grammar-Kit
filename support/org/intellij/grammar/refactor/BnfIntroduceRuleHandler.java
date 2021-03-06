/*
 * Copyright 2011-2011 Gregory Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.grammar.refactor;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pass;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.RefactoringActionHandler;
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser;
import gnu.trove.THashSet;
import org.intellij.grammar.generator.ParserGeneratorUtil;
import org.intellij.grammar.psi.*;
import org.intellij.grammar.psi.impl.BnfElementFactory;
import org.intellij.grammar.psi.impl.BnfFileImpl;
import org.intellij.grammar.psi.impl.GrammarUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * Date: 8/16/11
 * Time: 5:25 PM
 *
 * @author Vadim Romansky
 * @author gregsh
 */
public class BnfIntroduceRuleHandler implements RefactoringActionHandler {
  public static final String REFACTORING_NAME = "Extract Rule";

  @Override
  public void invoke(final @NotNull Project project, @NotNull PsiElement[] elements, DataContext dataContext) {
    // do not support this case
  }

  @Override
  public void invoke(@NotNull final Project project, final Editor editor, final PsiFile file, @Nullable DataContext dataContext) {
    if (!(file instanceof BnfFileImpl)) return;

    final BnfFileImpl bnfFile = (BnfFileImpl)file;
    final SelectionModel selectionModel = editor.getSelectionModel();
    int[] starts = selectionModel.getBlockSelectionStarts();
    int[] ends = selectionModel.getBlockSelectionEnds();
    if (starts.length == 0) return;

    int startOffset = starts[0];
    int endOffset = ends[ends.length-1];
    final BnfRule currentRule = PsiTreeUtil.getParentOfType(file.findElementAt(startOffset), BnfRule.class);
    if (currentRule == null) return;
    final BnfExpression parentExpression = findParentExpression(bnfFile, startOffset, endOffset-1);
    if (parentExpression == null) return;
    final List<BnfExpression> selectedExpression = findSelectedExpressionsInRange(parentExpression, new TextRange(startOffset, endOffset));
    if (selectedExpression.isEmpty()) return;
    final TextRange fixedRange = new TextRange(selectedExpression.get(0).getTextRange().getStartOffset(), selectedExpression.get(selectedExpression.size()-1).getTextRange().getEndOffset());
    final BnfRule ruleFromText = BnfElementFactory.createRuleFromText(file.getProject(), "a ::= " + fixedRange.substring(file.getText()));
    BnfExpressionOptimizer.optimize(ruleFromText.getExpression());

    final LinkedHashMap<OccurrencesChooser.ReplaceChoice, List<BnfExpression[]>> occurrencesMap = new LinkedHashMap<OccurrencesChooser.ReplaceChoice, List<BnfExpression[]>>();
    occurrencesMap.put(OccurrencesChooser.ReplaceChoice.NO, Collections.singletonList(selectedExpression.toArray(new BnfExpression[selectedExpression.size()])));
    occurrencesMap.put(OccurrencesChooser.ReplaceChoice.ALL, new ArrayList<BnfExpression[]>());
    file.acceptChildren(new PsiRecursiveElementWalkingVisitor() {
      @Override
      public void visitElement(PsiElement element) {
        if (element instanceof BnfExpression) {
          findOccurrences((BnfExpression)element, selectedExpression, occurrencesMap);
        }
        else if (element instanceof BnfAttrs) {
          return;
        }
        super.visitElement(element);
      }
    });
    if (occurrencesMap.get(OccurrencesChooser.ReplaceChoice.ALL).size() <= 1 && !ApplicationManager.getApplication().isUnitTestMode()) {
      occurrencesMap.remove(OccurrencesChooser.ReplaceChoice.ALL);
    }
    
    final Pass<OccurrencesChooser.ReplaceChoice> callback = new Pass<OccurrencesChooser.ReplaceChoice>() {
      @Override
      public void pass(final OccurrencesChooser.ReplaceChoice choice) {
        new WriteCommandAction.Simple(project, REFACTORING_NAME, file) {
          @Override
          public void run() {
            final PsiFile containingFile = currentRule.getContainingFile();
            String newRuleName = choseRuleName(containingFile);
            String newRuleText = "private  " + newRuleName + " ::= " + ruleFromText.getExpression().getText();
            BnfRule addedRule = addNextRule(project, currentRule, newRuleText);
            if (choice == OccurrencesChooser.ReplaceChoice.ALL) {
              List<BnfExpression[]> exprToReplace = occurrencesMap.get(OccurrencesChooser.ReplaceChoice.ALL);
              replaceUsages(project, exprToReplace, addedRule.getId());
            }
            else {
              List<BnfExpression[]> exprToReplace = occurrencesMap.get(OccurrencesChooser.ReplaceChoice.NO);
              replaceUsages(project, exprToReplace, addedRule.getId());
            }
            final BnfIntroduceRulePopup popup = new BnfIntroduceRulePopup(project, editor, addedRule, addedRule.getExpression());

            editor.getCaretModel().moveToOffset(addedRule.getTextOffset());
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.getDocument());
            popup.performInplaceRefactoring(null);
          }
        }.execute();
      }
    };
    if(ApplicationManager.getApplication().isUnitTestMode()){
      callback.pass(OccurrencesChooser.ReplaceChoice.ALL);
    } else {
      new OccurrencesChooser<BnfExpression[]>(editor){
        @Override
        protected TextRange getOccurrenceRange(BnfExpression[] occurrence) {
          return new TextRange(occurrence[0].getTextRange().getStartOffset(), occurrence[occurrence.length-1].getTextRange().getEndOffset());
        }
      }.showChooser(callback, occurrencesMap);
    }
  }

  public static BnfRule addNextRule(Project project, BnfRule currentRule, String newRuleText) {
    BnfRule addedRule = (BnfRule)currentRule.getParent().addAfter(BnfElementFactory.createRuleFromText(project, newRuleText), currentRule);
    currentRule.getParent().addBefore(BnfElementFactory.createLeafFromText(project, "\n"), addedRule);
    if (endsWithSemicolon(currentRule)) {
      addedRule.addBefore(BnfElementFactory.createLeafFromText(project, ";"), null);
      if (currentRule.getNextSibling() instanceof PsiWhiteSpace) {
        currentRule.getParent().addAfter(BnfElementFactory.createLeafFromText(project, "\n"), addedRule);
      }
    }
    return addedRule;
  }

  public static boolean endsWithSemicolon(BnfRule rule) {
    return rule.getLastChild().getNode().getElementType() == BnfTypes.BNF_SEMICOLON;
  }

  private static List<BnfExpression> findSelectedExpressionsInRange(BnfExpression parentExpression, TextRange range) {
    if (parentExpression.getTextRange().equals(range)) {
      if (parentExpression instanceof BnfSequence) return ((BnfSequence)parentExpression).getExpressionList();
      if (parentExpression instanceof BnfChoice) return ((BnfChoice)parentExpression).getExpressionList();
      return Collections.singletonList(parentExpression);
    }
    LinkedList<BnfExpression> list = new LinkedList<BnfExpression>();
    for (PsiElement c = parentExpression.getFirstChild(); c != null; c = c.getNextSibling()) {
      if (c instanceof PsiWhiteSpace) continue;
      if (c.getTextRange().intersectsStrict(range)) {
        if (c instanceof BnfExpression) {
          list.add((BnfExpression)c);
        }
        else if (c == parentExpression.getFirstChild() || c == parentExpression.getLastChild()) {
          return Collections.singletonList(parentExpression);
        }
      }
    }
    return list;
  }

  private static void replaceUsages(Project project, List<BnfExpression[]> exprToReplace, PsiElement id) {
    for (BnfExpression[] expression : exprToReplace) {
      replaceExpression(project, expression, id);
    }
  }

  private static void replaceExpression(Project project, BnfExpression[] oldExpression, PsiElement id) {
    PsiElement parent = oldExpression[0].getParent();
    parent.addBefore(BnfElementFactory.createRuleFromText(project, "a::="+id.getText()).getExpression(), oldExpression[0]);
    parent.deleteChildRange(oldExpression[0], oldExpression[oldExpression.length - 1]);
    //BnfExpressionOptimizer.optimize(parent);
  }

  private static void findOccurrences(BnfExpression expression,
                                      List<BnfExpression> selectedExpressions,
                                      Map<OccurrencesChooser.ReplaceChoice, List<BnfExpression[]>> occurrencesMap) {
    if (selectedExpressions.size() == 1) {
      if (GrammarUtil.equalsElement(expression, selectedExpressions.get(0))) {
        addOccurrence(OccurrencesChooser.ReplaceChoice.ALL, occurrencesMap, expression);
      }
    }
    else if (!GrammarUtil.isOneTokenExpression(expression)) {
      final PsiElement selectedParent = selectedExpressions.get(0).getParent();
      if (ParserGeneratorUtil.getEffectiveType(expression) != ParserGeneratorUtil.getEffectiveType(selectedParent)) return; 
      int pos = 0;
      BnfExpression[] result = new BnfExpression[selectedExpressions.size()];
      for (PsiElement c = expression.getFirstChild(), s = null; c != null; c = c.getNextSibling()) {
        if (!(c instanceof BnfExpression)) continue;
        if (GrammarUtil.equalsElement((BnfExpression)c, selectedExpressions.get(pos))) {
          if (pos == 0) s = c;
          result[pos] = (BnfExpression)c;
          if (++ pos == result.length) {
            addOccurrence(OccurrencesChooser.ReplaceChoice.ALL, occurrencesMap, result.clone());
            pos = 0;
          }
        }
        else if (s != null) {
          c = s;
          pos = 0;
          s = null;
        }
      }
    }
  }

  private static void addOccurrence(OccurrencesChooser.ReplaceChoice choice,
                                    Map<OccurrencesChooser.ReplaceChoice, List<BnfExpression[]>> occurrencesMap,
                                    BnfExpression... expressions) {
    List<BnfExpression[]> list = occurrencesMap.get(choice);
    if (list == null) occurrencesMap.put(choice, list = new LinkedList<BnfExpression[]>());
    list.add(expressions);
  }

  private static String choseRuleName(PsiFile containingFile) {
    final Set<String> existingNames = new THashSet<String>();
    containingFile.accept(new PsiRecursiveElementWalkingVisitor() {
      @Override
      public void visitElement(PsiElement element) {
        if (element instanceof BnfAttrs) return;
        if (element instanceof BnfReferenceOrToken) {
          existingNames.add(((BnfReferenceOrToken)element).getId().getText());
        }
        else if (element instanceof BnfRule) {
          existingNames.add(((BnfRule)element).getId().getText());
        }
        super.visitElement(element);
      }
    });
    String name = "rule";
    for (int i = 1; existingNames.contains(name); i++) {
      name = "rule" + i;
    }
    return name;
  }

  @Nullable
  private static BnfExpression findParentExpression(PsiFile file, int startOffset, int endOffset) {
    final PsiElement startElement = file.findElementAt(startOffset);
    final PsiElement endElement = file.findElementAt(endOffset);
    if (startElement == null || endElement == null) return null;
    final PsiElement commonParent = PsiTreeUtil.findCommonParent(startElement, endElement);
    return PsiTreeUtil.getParentOfType(commonParent, BnfExpression.class, false);
  }
}
