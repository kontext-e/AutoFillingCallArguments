package de.kontext_e.idea.plugins.autofill;

import static com.intellij.openapi.command.UndoConfirmationPolicy.DEFAULT;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.swing.*;

import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.codeInsight.hint.ShowParameterInfoContext;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.lang.Language;
import com.intellij.lang.parameterInfo.LanguageParameterInfo;
import com.intellij.lang.parameterInfo.ParameterInfoHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiCallExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.infos.CandidateInfo;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.components.JBList;
import com.intellij.util.IncorrectOperationException;

public class AutoFillCallArguments extends PsiElementBaseIntentionAction implements IntentionAction {
    @Override
    public void invoke(@NotNull final Project project, final Editor editor, @NotNull final PsiElement psiElement) throws IncorrectOperationException {
        if (editor == null) {
            return;
        }

        if (!ApplicationManager.getApplication().isDispatchThread()) {
            return;
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments();

        final var psiMethods = resolveMethodFromCandidates(project, editor, psiElement);
        if (psiMethods.isEmpty()) {
            return;
        }
        if (psiMethods.size() == 1) {
            insertParameters(editor, psiMethods.iterator().next());
        } else {
            insertParameters(project, editor, psiMethods);
        }
    }

    private void insertParameters(final Project project, final Editor editor, final Collection<PsiMethod> psiMethods) {
        // Create a popup dialog that displays the list of options
        final var methodsWrapped = psiMethods.stream()
                                             .map(PsiMethodWrapper::new)
                                             .collect(Collectors.toList());


        final var model = new DefaultListModel<PsiMethodWrapper>();
        final var list = new JBList<>(model);
        methodsWrapped.forEach(model::addElement);

        final var builder = new PopupChooserBuilder<>(list);

        builder.setItemChosenCallback(selectedOption ->
                                          ApplicationManager.getApplication().invokeLater(() ->
                                                                                              ApplicationManager.getApplication().runWriteAction(() -> {
                                                                                                  final var doc = editor.getDocument();
                                                                                                  CommandProcessor.getInstance().executeCommand(
                                                                                                      project,
                                                                                                      () -> insertParameters(editor, selectedOption.method),
                                                                                                      "Add Auto Parameters",
                                                                                                      null,
                                                                                                      DEFAULT,
                                                                                                      doc);
                                                                                              })));

        builder.createPopup().showInBestPositionFor(editor);
    }

    private static void insertParameters(final Editor editor, final PsiMethod psiMethod) {
        final PsiParameterList parameterList = psiMethod.getParameterList();
        final PsiParameter[] params = parameterList.getParameters();
        final var doc = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        final var textUnderCaret = doc.getText(new TextRange(offset, offset + 1));
        final var textLeftOfCaret = doc.getText(new TextRange(offset - 1, offset));
        if (textLeftOfCaret.startsWith("(")) {
            // do nothing
        } else if (textUnderCaret.startsWith("(")) {
            offset++;
        } else if (!textUnderCaret.startsWith(")")) {
            offset--;
        }
        final var insertString = Arrays.stream(params)
                                       .map(PsiParameter::getName)
                                       .collect(Collectors.joining(", "));
        doc.insertString(offset, insertString);
        editor.getCaretModel().moveToOffset(offset + insertString.length() + 1);
    }

    @Override
    public boolean isAvailable(@NotNull final Project project, final Editor editor, @NotNull final PsiElement psiElement) {
        final PsiCallExpression psiCallExpression = findParent(PsiCallExpression.class, psiElement);
        return psiCallExpression != null;
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
        return getText();
    }

    @NotNull
    @Override
    public String getText() {
        return "Auto fill call parameters";
    }

    @Nullable
    private <T> T findParent(final Class<T> aClass, final PsiElement element) {
        if (element == null || aClass == null) {
            return null;
        } else if (PsiClass.class.isAssignableFrom(element.getClass())) {
            return null;
        } else if (aClass.isAssignableFrom(element.getClass())) {
            return aClass.cast(element);
        } else {
            return findParent(aClass, element.getParent());
        }
    }

    private Collection<PsiMethod> resolveMethodFromCandidates(@NotNull final Project project, final Editor editor, @NotNull final PsiElement psiElement) {
        final PsiFile file = PsiUtilBase.getPsiFileInEditor(editor, project);

        final int offset = editor.getCaretModel().getOffset();
        final int fileLength = file.getTextLength();

        final ShowParameterInfoContext context = new ShowParameterInfoContext(editor, project, file, offset, -1, false, false);

        final int offsetForLangDetection = offset > 0 && offset == fileLength ? offset - 1 : offset;
        final Language language = PsiUtilCore.getLanguageAtOffset(file, offsetForLangDetection);

        return DumbService.getInstance(project).computeWithAlternativeResolveEnabled(
            () -> getParameterInfoHandlers(project, file, language).stream()
                                                                   .map(handler -> handler.findElementForParameterInfo(context))
                                                                   .filter(Objects::nonNull)
                                                                   .flatMap(element -> Arrays.stream(context.getItemsToShow()))
                                                                   .filter(CandidateInfo.class::isInstance)
                                                                   .map(CandidateInfo.class::cast)
                                                                   .map(CandidateInfo::getElement)
                                                                   .filter(PsiMethod.class::isInstance)
                                                                   .map(PsiMethod.class::cast)
                                                                   .filter(PsiMethod::hasParameters)
                                                                   .collect(Collectors.toList()));
    }

    @NotNull
    private static Collection<ParameterInfoHandler> getParameterInfoHandlers(@NotNull final Project project, @NotNull final PsiFile file, @NotNull final Language language) {
        final var handlers = new HashSet<ParameterInfoHandler>();
        final var dumbService = DumbService.getInstance(project);
        for (final var currentLanguage : List.of(language, file.getViewProvider().getBaseLanguage())) {
            handlers.addAll(dumbService.filterByDumbAwareness(LanguageParameterInfo.INSTANCE.allForLanguage(currentLanguage)));
        }
        return handlers;
    }

    private class PsiMethodWrapper {
        private final PsiMethod method;

        public PsiMethodWrapper(final PsiMethod method) {
            this.method = method;
        }

        public PsiMethod getMethod() {
            return method;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            final PsiMethodWrapper that = (PsiMethodWrapper) o;
            return Objects.equals(method, that.method);
        }

        @Override
        public int hashCode() {
            return Objects.hash(method);
        }

        @Override
        public String toString() {
            return Arrays.stream(method.getParameterList().getParameters())
                         .map(p -> p.getType().getCanonicalText() + "  " + p.getName())
                         .collect(Collectors.joining(", "));
        }
    }
}
