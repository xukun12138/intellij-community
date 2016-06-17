/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.jetbrains.edu.learning.stepic;

import com.intellij.ide.BrowserUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class LoginPanel {
  private JPanel myPane;
  private JTextField myLoginTextField;
  private JPasswordField myPasswordField;
  private JBLabel mySignUpLabel;

  public LoginPanel(final LoginDialog dialog) {
    DocumentListener listener = new DocumentAdapter() {
      @Override
      protected void textChanged(DocumentEvent e) {
        dialog.clearErrors();
      }
    };
    myLoginTextField.getDocument().addDocumentListener(listener);
    myPasswordField.getDocument().addDocumentListener(listener);

    mySignUpLabel.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        BrowserUtil.browse(EduStepicNames.STEPIC_SIGN_IN_LINK);
      }

      @Override
      public void mouseEntered(MouseEvent e) {
        e.getComponent().setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      }

      @Override
      public void mouseExited(MouseEvent e) {
        e.getComponent().setCursor(Cursor.getDefaultCursor());
      }
    });
  }

  public JComponent getPanel() {
    return myPane;
  }

  public void setLogin(@Nullable String login) {
    myLoginTextField.setText(login);
  }

  @NotNull
  private String getLogin() {
    return myLoginTextField.getText().trim();
  }

  @NotNull
  private String getPassword() {
    return String.valueOf(myPasswordField.getPassword());
  }

  public JComponent getPreferableFocusComponent() {
    return myLoginTextField.isVisible() ? myLoginTextField : myPasswordField;
  }

  @NotNull
  public LoginDialog.AuthDataHolder getAuthData() {
    return new LoginDialog.AuthDataHolder(getLogin(), getPassword());
  }
}

