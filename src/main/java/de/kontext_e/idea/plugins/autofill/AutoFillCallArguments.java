package de.kontext_e.idea.plugins.autofill;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.intellij.codeInsight.hint.ShowParameterInfoContext;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.lang.Language;
import com.intellij.lang.parameterInfo.LanguageParameterInfo;
import com.intellij.lang.parameterInfo.ParameterInfoHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
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
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class AutoFillCallArguments extends PsiElementBaseIntentionAction implements IntentionAction {
    @Override
    public void invoke(@NotNull final Project project, final Editor editor, @NotNull final PsiElement psiElement) throws IncorrectOperationException {
        ApplicationManager.getApplication().assertIsDispatchThread();
        PsiDocumentManager.getInstance(project).commitAllDocuments();

        // Create a popup dialog that displays the list of options

        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JBList<>( model );
        List.of("A","B","C").forEach(e->  model.addElement( e ));


        PopupChooserBuilder<String> builder = new PopupChooserBuilder<>(list);
        builder.setTitle("Choose an option");
        builder.setItemChosenCallback((selectedOption) -> {
            // Handle the selected option here
            System.out.println("Selected option: " + selectedOption);
        });
        builder.createPopup().showInBestPositionFor(editor);


        if(psiElement == null) {
            return;
        }
        final PsiCallExpression call = findParent(PsiCallExpression.class, psiElement);
        if(call == null) {
            return;
        }

        PsiMethod psiMethod = call.resolveMethod();
        if(psiMethod == null) {
            psiMethod = resolveMethodFromCandidates(project, editor, psiElement);
            if(psiMethod == null) {
                return;
            }
        }
        final PsiParameterList parameterList = psiMethod.getParameterList();
        if(parameterList == null) {
            return;
        }
        final PsiParameter[] params = parameterList.getParameters();
        if(params == null) {
            return;
        }
        String prefix = "";
        int offset = editor.getCaretModel().getOffset();
        final Document doc = editor.getDocument();
        for(final PsiParameter p : params) {
            doc.insertString(offset, prefix+p.getName());
            offset += p.getName().length() + prefix.length();
            prefix = ", ";
        }
        editor.getCaretModel().moveToOffset(offset + 1);
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

    private <T> T findParent(final Class<T> aClass, final PsiElement element) {
        if (element == null) return null;
        else if (PsiClass.class.isAssignableFrom(element.getClass())) return null;
        else if (aClass.isAssignableFrom(element.getClass())) return aClass.cast(element);
        else return findParent(aClass, element.getParent());
    }

    private PsiMethod resolveMethodFromCandidates(@NotNull final Project project, final Editor editor, @NotNull final PsiElement psiElement) {
        final PsiFile file = project == null ? null : PsiUtilBase.getPsiFileInEditor(editor, project);

        final int offset = editor.getCaretModel().getOffset();
        final int fileLength = file.getTextLength();

        final ShowParameterInfoContext context = new ShowParameterInfoContext(
            editor,
            project,
            file,
            offset,
            -1,
            false,
            false
        );

        final int offsetForLangDetection = offset > 0 && offset == fileLength ? offset - 1 : offset;
        final Language language = PsiUtilCore.getLanguageAtOffset(file, offsetForLangDetection);
        ParameterInfoHandler[] handlers = getHandlers(project, language, file.getViewProvider().getBaseLanguage());

        if (handlers == null) handlers = new ParameterInfoHandler[0];

        PsiMethod psiMethod = null;
        int mostNumberOfParameters = 0;
        DumbService.getInstance(project).setAlternativeResolveEnabled(true);
        try {
            for (final ParameterInfoHandler handler : handlers) {
                final Object element = handler.findElementForParameterInfo(context);
                if (element != null) {
                    final Object[] itemsToShow = context.getItemsToShow();
                    for(final Object item : itemsToShow) {
                        if(item instanceof CandidateInfo) {
                            final CandidateInfo candidate = (CandidateInfo)item;
                            final PsiElement candidateElement = candidate.getElement();
                            if(candidateElement instanceof PsiMethod) {
                                final PsiMethod candidatePsiMethod = (PsiMethod)candidateElement;
                                final PsiParameterList parameterList = candidatePsiMethod.getParameterList();
                                if(parameterList != null) {
                                    final PsiParameter[] params = parameterList.getParameters();
                                    if (params != null) {
                                        if(params.length > mostNumberOfParameters) {
                                            mostNumberOfParameters = params.length;
                                            psiMethod = candidatePsiMethod;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return psiMethod;
        }
        finally {
            DumbService.getInstance(project).setAlternativeResolveEnabled(false);
        }
    }

    @Nullable
    public static ParameterInfoHandler[] getHandlers(final Project project, final Language... languages) {
        final Set<ParameterInfoHandler> handlers = new LinkedHashSet<>();
        final DumbService dumbService = DumbService.getInstance(project);
        for (final Language language : languages) {
            handlers.addAll(dumbService.filterByDumbAwareness(LanguageParameterInfo.INSTANCE.allForLanguage(language)));
        }
        if (handlers.isEmpty()) return null;
        return handlers.toArray(new ParameterInfoHandler[0]);
    }

}
