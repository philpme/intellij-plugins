/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.coldFusion.UI.editorActions.structureView;

import com.intellij.coldFusion.model.psi.CfmlFunction;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TextEditorBasedStructureViewModel;
import com.intellij.ide.util.treeView.smartTree.Filter;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Created by Lera Nikolaenko
 */
public class CfmlStructureViewModel extends TextEditorBasedStructureViewModel {
  private final StructureViewTreeElement myRoot;
  private final Class[] myClasses = {CfmlFunction.class};

  protected CfmlStructureViewModel(@NotNull PsiFile psiFile, @Nullable Editor editor) {
    super(editor, psiFile);
    myRoot = new CfmlStructureViewElement(getPsiFile());
  }

  @Override
  @NotNull
  public StructureViewTreeElement getRoot() {
    return myRoot;
  }

  @Override
  public Sorter @NotNull [] getSorters() {
    return new Sorter[]{Sorter.ALPHA_SORTER};
  }

  @Override
  public Filter @NotNull [] getFilters() {
    return super.getFilters();
  }

  @Override
  protected Class @NotNull [] getSuitableClasses() {
    return myClasses;
  }
}
