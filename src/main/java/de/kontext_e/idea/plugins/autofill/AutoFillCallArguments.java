package de.kontext_e.idea.plugins.autofill;

import com.intellij.codeInsight.hint.ShowParameterInfoContext;
import com.intellij.codeInsight.hint.api.impls.MethodParameterInfoHandler;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.lang.Language;
import com.intellij.lang.parameterInfo.LanguageParameterInfo;
import com.intellij.lang.parameterInfo.ParameterInfoHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
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
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.components.JBList;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import static com.intellij.openapi.command.UndoConfirmationPolicy.DEFAULT;

@Service
public final class AutoFillCallArguments extends PsiElementBaseIntentionAction implements IntentionAction {

    @Override
    public void invoke(@NotNull final Project project, final Editor editor, @NotNull final PsiElement psiElement) throws IncorrectOperationException {
        if (editor == null) {return; }
        if (!ApplicationManager.getApplication().isDispatchThread()) { return; }

        PsiDocumentManager.getInstance(project).commitAllDocuments();

        final var psiMethods = resolveMethodFromCandidates(project, editor);
        if (psiMethods.isEmpty()) { return; }

        if (psiMethods.size() == 1) {
            insertParameters(editor, psiMethods.iterator().next());
        } else {
            insertParametersForOverloadedMethod(project, editor, psiMethods);
        }
    }

    private Collection<PsiMethod> resolveMethodFromCandidates(@NotNull final Project project, final Editor editor) {
        final PsiFile file = PsiUtilBase.getPsiFileInEditor(editor, project);
        final int offset = editor.getCaretModel().getOffset();
        final int fileLength = file.getTextLength();

        final ShowParameterInfoContext context = new ShowParameterInfoContext(editor, project, file, offset, -1, false, false);

        final int offsetForLangDetection = offset > 0 && offset == fileLength ? offset - 1 : offset;
        final Language language = PsiUtilCore.getLanguageAtOffset(file, offsetForLangDetection);

        return DumbService.getInstance(project).computeWithAlternativeResolveEnabled(
                () -> getParameterInfoHandlers(project, file, language)
                        .stream()
                        .map(handler -> handler.findElementForParameterInfo(context))
                        .filter(Objects::nonNull)
                        .flatMap(element -> Arrays.stream(context.getItemsToShow()))
                        .map(MethodParameterInfoHandler::tryGetMethodFromCandidate)
                        .filter(Objects::nonNull)
                        .filter(PsiMethod::hasParameters)
                        .toList()
        );
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

    private void insertParametersForOverloadedMethod(final Project project, final Editor editor, final Collection<PsiMethod> psiMethods) {
        // Wrap methods to customize toString() behavior
        final var methodsWrapped = psiMethods.stream().map(PsiMethodWrapper::new).toList();
        final var model = new DefaultListModel<PsiMethodWrapper>();
        final var list = new JBList<>(model);
        methodsWrapped.forEach(model::addElement);

        // Create a popup dialog that displays the list of options
        final var builder = new PopupChooserBuilder<>(list);

        builder.setItemChosenCallback(
                selectedOption -> ApplicationManager.getApplication().invokeLater(
                        () -> ApplicationManager.getApplication().runWriteAction(
                                () -> insertParameterForSelectedOption(project, editor, selectedOption)
                        )
                )
        );

        builder.createPopup().showInBestPositionFor(editor);
    }

    private static void insertParameterForSelectedOption(final Project project, final Editor editor, final PsiMethodWrapper selectedOption) {
        // Command that will get executed when option is chosen
        CommandProcessor.getInstance().executeCommand(
                project,
                () -> insertParameters(editor, selectedOption.method),
                "Add Auto Parameters",
                null,
                DEFAULT,
                editor.getDocument()
        );
    }

    private static void insertParameters(final Editor editor, final PsiMethod psiMethod) {
        final PsiParameterList parameterList = psiMethod.getParameterList();
        final PsiParameter[] params = parameterList.getParameters();
        final int offset = findCaretOffset(editor, editor.getDocument());

        final var insertString = Arrays.stream(params)
                .map(PsiParameter::getName)
                .collect(Collectors.joining(", "));

        editor.getDocument().insertString(offset, insertString);
        editor.getCaretModel().moveToOffset(offset + insertString.length() + 1);
    }

    private static int findCaretOffset(final Editor editor, final Document doc) {
        int offset = editor.getCaretModel().getOffset();
        final var textUnderCaret = doc.getText(new TextRange(offset, offset + 1));
        final var textLeftOfCaret = doc.getText(new TextRange(offset - 1, offset));

        if (textLeftOfCaret.startsWith("(")) {
            // caret is already at the right spot; do nothing
        } else if (textUnderCaret.startsWith("(")) {
            offset++;
        } else if (!textUnderCaret.startsWith(")")) {
            offset--;
        }

        return offset;
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

    private record PsiMethodWrapper(PsiMethod method) {

        @Override
        public String toString() {
            final StringJoiner parametersInfo = new StringJoiner(", ");

            ApplicationManager.getApplication().runReadAction(() -> {
                for (final PsiParameter parameter : method.getParameterList().getParameters()) {
                    parametersInfo.add(parameter.getType().getCanonicalText() + " " + parameter.getName());
                }
            });

            return parametersInfo.toString();
        }
    }
}