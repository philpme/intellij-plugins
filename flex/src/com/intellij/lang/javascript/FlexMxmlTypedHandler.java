// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.lang.javascript;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlText;
import org.jetbrains.annotations.NotNull;

public class FlexMxmlTypedHandler extends TypedHandlerDelegate {
  @NotNull
  @Override
  public Result beforeCharTyped(char c,
                                @NotNull Project project,
                                @NotNull Editor editor,
                                @NotNull PsiFile file,
                                @NotNull FileType fileType) {
    if (c == '}' && JavaScriptSupportLoader.isFlexMxmFile(file)) {
      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
      int offset = editor.getCaretModel().getOffset();
      PsiElement at = file.findElementAt(offset);
      PsiElement value = PsiTreeUtil.getNonStrictParentOfType(at, XmlAttributeValue.class, XmlText.class);
      if (value != null && editor.getDocument().getCharsSequence().charAt(offset) == '}') {
        editor.getCaretModel().moveToOffset(offset + 1);
        return Result.STOP;
      }
    }
    return super.beforeCharTyped(c, project, editor, file, fileType);
  }

  @NotNull
  @Override
  public Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    if (c == '{' && JavaScriptSupportLoader.isFlexMxmFile(file)) {
      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
      int offset = editor.getCaretModel().getOffset();
      PsiElement at = file.findElementAt(offset);
      PsiElement value = PsiTreeUtil.getParentOfType(at, XmlAttributeValue.class, XmlText.class);
      if (value != null) {
        String s = StringUtil.unquoteString(value.getText());
        int lbraceCount = 0;
        int rbraceCount = 0;

        for(int i = 0; i < s.length(); ++i) {
          char ch = s.charAt(i);
          if (ch == '{') ++lbraceCount;
          else if (ch == '}') ++rbraceCount;
        }

        if (lbraceCount == rbraceCount + 1) {
          editor.getDocument().insertString(offset, "}");
          return Result.STOP;
        }
      }
    }
    return super.charTyped(c, project, editor, file);
  }
}
