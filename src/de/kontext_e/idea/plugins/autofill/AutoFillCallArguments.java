package de.kontext_e.idea.plugins.autofill;

import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiCallExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiParameter;
import com.intellij.util.IncorrectOperationException;

public class AutoFillCallArguments extends PsiElementBaseIntentionAction implements IntentionAction {
    @Override
    public void invoke(@NotNull final Project project, final Editor editor, @NotNull final PsiElement psiElement) throws IncorrectOperationException {
        PsiCallExpression call = findParent(PsiCallExpression.class, psiElement);
        if(call == null) {
            return;
        }

        Document doc = editor.getDocument();

        PsiParameter[] params = call.resolveMethod().getParameterList().getParameters();
        String prefix = "";
        int offset = editor.getCaretModel().getOffset();
        for(PsiParameter p : params) {
            doc.insertString(offset, prefix+p.getName());
            offset += p.getName().length() + prefix.length();
            prefix = ", ";
        }
        editor.getCaretModel().moveToOffset(offset + 1);
    }

    @Override
    public boolean isAvailable(@NotNull final Project project, final Editor editor, @NotNull final PsiElement psiElement) {
        return findParent(PsiCallExpression.class, psiElement) != null;
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

    private <T> T findParent(Class<T> aClass, PsiElement element) {
        if (element == null) return null;
        else if (PsiClass.class.isAssignableFrom(element.getClass())) return null;
        else if (aClass.isAssignableFrom(element.getClass())) return aClass.cast(element);
        else return findParent(aClass, element.getParent());
    }

}
