// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.debugger.extensions;

import com.intellij.lang.LangBundle;
import com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class UiScriptFileType implements FileType {
  private static UiScriptFileType myInstance;

  private UiScriptFileType() {
  }

  @Override
  public @NotNull CharsetHint getCharsetHint() {
    return new CharsetHint.ForcedCharset(StandardCharsets.UTF_8);
  }

  public static UiScriptFileType getInstance() {
    if (myInstance == null) {
      myInstance = new UiScriptFileType();
    }
    return myInstance;
  }

  @Override
  @NotNull
  public String getName() {
    return "UI Script";
  }

  @Override
  @NotNull
  public String getDescription() {
    return LangBundle.message("ui.script.file.type.description");
  }

  public static final String myExtension = "ijs";

  @Override
  @NotNull
  public String getDefaultExtension() {
    return myExtension;
  }

  @Override
  public Icon getIcon() {
    return null;
  }

  @Override
  public boolean isBinary() {
    return false;
  }
}
