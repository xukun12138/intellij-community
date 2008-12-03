package com.intellij.ui.tabs.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ShadowAction;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Getter;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.wm.FocusCommand;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeGlassPane;
import com.intellij.openapi.wm.IdeGlassPaneUtil;
import com.intellij.openapi.wm.impl.content.GraphicsConfig;
import com.intellij.ui.CaptionPanel;
import com.intellij.ui.tabs.*;
import com.intellij.ui.tabs.impl.singleRow.SingleRowLayout;
import com.intellij.ui.tabs.impl.table.TableLayout;
import com.intellij.ui.tabs.impl.table.TablePassInfo;
import com.intellij.util.ui.Animator;
import com.intellij.util.ui.TimedDeadzone;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.ComponentUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;

public class JBTabsImpl extends JComponent
    implements JBTabs, PropertyChangeListener, TimerListener, DataProvider, PopupMenuListener, Disposable, JBTabsPresentation {

  static DataKey<JBTabsImpl> NAVIGATION_ACTIONS_KEY = DataKey.create("JBTabs");

  ActionManager myActionManager;
  public final List<TabInfo> myVisibleInfos = new ArrayList<TabInfo>();
  final Set<TabInfo> myHiddenInfos = new HashSet<TabInfo>();

  TabInfo mySelectedInfo;
  public final Map<TabInfo, TabLabel> myInfo2Label = new HashMap<TabInfo, TabLabel>();
  public final Map<TabInfo, JComponent> myInfo2Toolbar = new HashMap<TabInfo, JComponent>();
  public Dimension myHeaderFitSize;

  Insets myInnerInsets = new Insets(0, 0, 0, 0);

  final List<EventListener> myTabMouseListeners = new ArrayList<EventListener>();
  final List<TabsListener> myTabListeners = new ArrayList<TabsListener>();
  public boolean myFocused;

  Getter<ActionGroup> myPopupGroup;
  String myPopupPlace;

  TabInfo myPopupInfo;
  DefaultActionGroup myNavigationActions;

  PopupMenuListener myPopupListener;
  JPopupMenu myActivePopup;

  public boolean myHorizontalSide = true;

  boolean myStealthTabMode = false;

  DataProvider myDataProvider;

  WeakHashMap<Component, Component> myDeferredToRemove = new WeakHashMap<Component, Component>();

  SingleRowLayout mySingleRowLayout = new SingleRowLayout(this);
  TableLayout myTableLayout = new TableLayout(this);


  private TabLayout myLayout = mySingleRowLayout;
  private LayoutPassInfo myLastLayoutPass;

  public boolean myForcedRelayout;

  private UiDecorator myUiDecorator;
  static final UiDecorator ourDefaultDecorator = new DefautDecorator();

  private boolean myPaintFocus = true;

  private boolean myHideTabs = false;
  private @Nullable Project myProject;

  private boolean myRequestFocusOnLastFocusedComponent = false;
  private boolean myListenerAdded;
  final Set<TabInfo> myAttractions = new HashSet<TabInfo>();
  Animator myAnimator;
  List<TabInfo> myAllTabs;
  boolean myPaintBlocked;
  BufferedImage myImage;
  IdeFocusManager myFocusManager;
  boolean myAdjustBorders = true;


  private Insets myBorderSize = new Insets(0, 0, 0, 0);
  boolean myAddNavigationGroup = true;

  boolean myGhostsAlwaysVisible = false;
  private boolean myDisposed;
  private boolean myToDrawBorderIfTabsHidden = true;
  private Color myActiveTabFillIn;

  private boolean myTabLabelActionsAutoHide;

  private TabActionsAutoHideListener myTabActionsAutoHideListener = new TabActionsAutoHideListener();
  private IdeGlassPane myGlassPane;
  @NonNls private static final String LAYOUT_DONE = "Layout.done";

  private TimedDeadzone.Length myTabActionsMouseDeadzone = TimedDeadzone.DEFAULT;

  private long myRemoveDefferredRequest;
  private boolean myTestMode;

  JBTabsPosition myPosition = JBTabsPosition.top;

  public JBTabsImpl(@Nullable Project project, ActionManager actionManager, IdeFocusManager focusManager, Disposable parent) {
    myProject = project;
    myActionManager = actionManager;
    myFocusManager = focusManager;

    setOpaque(true);
    setPaintBorder(-1, -1, -1, -1);

    Disposer.register(parent, this);

    myNavigationActions = new DefaultActionGroup();

    if (myActionManager != null) {
      myNavigationActions.add(new SelectNextAction(this));
      myNavigationActions.add(new SelectPreviousAction(this));
    }

    setUiDecorator(null);

    UIUtil.addAwtListener(new AWTEventListener() {
      public void eventDispatched(final AWTEvent event) {
        if (mySingleRowLayout.myMorePopup != null) return;
        processFocusChange();
      }
    }, FocusEvent.FOCUS_EVENT_MASK, this);

    myPopupListener = new PopupMenuListener() {
      public void popupMenuWillBecomeVisible(final PopupMenuEvent e) {
      }

      public void popupMenuWillBecomeInvisible(final PopupMenuEvent e) {
        disposePopupListener();
      }

      public void popupMenuCanceled(final PopupMenuEvent e) {
        disposePopupListener();
      }
    };

    addMouseListener(new MouseAdapter() {
      public void mousePressed(final MouseEvent e) {
        if (mySingleRowLayout.myLastSingRowLayout != null &&
            mySingleRowLayout.myLastSingRowLayout.moreRect != null &&
            mySingleRowLayout.myLastSingRowLayout.moreRect.contains(e.getPoint())) {
          showMorePopup(e);
        }
      }
    });

    Disposer.register(this, new Disposable() {
      public void dispose() {
        removeTimerUpdate();
      }
    });

    myAnimator = new Animator("JBTabs Attractions", 2, 500, true, 0, -1) {
      public void paintNow(final float frame, final float totalFrames, final float cycle) {
        repaintAttractions();
      }
    };
    myAnimator.setTakInitialDelay(false);

    Disposer.register(this, myAnimator);

    setFocusCycleRoot(true);
    setFocusTraversalPolicy(new LayoutFocusTraversalPolicy() {
      public Component getDefaultComponent(final Container aContainer) {
        return getToFocus();
      }
    });

    add(mySingleRowLayout.myLeftGhost);
    add(mySingleRowLayout.myRightGhost);
  }

  public void dispose() {
    myDisposed = true;
    mySelectedInfo = null;
    myAllTabs = null;
    myAttractions.clear();
    myVisibleInfos.clear();
    myUiDecorator = null;
    myImage = null;
    myActivePopup = null;
    myInfo2Label.clear();
    myInfo2Toolbar.clear();
    myTabListeners.clear();
  }

  private void processFocusChange() {
    Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    if (owner == null) {
      setFocused(false);
      return;
    }

    if (owner == JBTabsImpl.this || SwingUtilities.isDescendingFrom(owner, JBTabsImpl.this)) {
      setFocused(true);
    }
    else {
      setFocused(false);
    }
  }

  private void repaintAttractions() {
    boolean needsUpdate = false;
    for (TabInfo each : myVisibleInfos) {
      TabLabel eachLabel = myInfo2Label.get(each);
      needsUpdate |= eachLabel.repaintAttraction();
    }

    if (needsUpdate) {
      relayout(true, false);
    }
  }

  public void addNotify() {
    super.addNotify();

    if (myActionManager != null && !myListenerAdded) {
      myActionManager.addTimerListener(500, this);
      myListenerAdded = true;
    }

    if (!myTestMode) {
      final IdeGlassPane gp = IdeGlassPaneUtil.find(this);
      if (gp != null) {
        gp.addMouseMotionPreprocessor(myTabActionsAutoHideListener, this);
        myGlassPane = gp;
      }
    }
  }

  public void removeNotify() {
    super.removeNotify();

    setFocused(false);

    removeTimerUpdate();

    if (myGlassPane != null) {
      myGlassPane.removeMouseMotionPreprocessor(myTabActionsAutoHideListener);
      myGlassPane = null;
    }
  }

  void setTestMode(final boolean testMode) {
    myTestMode = testMode;
  }

  class TabActionsAutoHideListener extends MouseMotionAdapter {

    private TabLabel myCurrentOverLabel;
    private Point myLastOverPoint;

    @Override
    public void mouseMoved(final MouseEvent e) {
      if (!myTabLabelActionsAutoHide) return;

      final Point point = SwingUtilities.convertPoint(e.getComponent(), e.getX(), e.getY(), JBTabsImpl.this);
      myLastOverPoint = point;
      processMouseOver();
    }

    void processMouseOver() {
      if (!myTabLabelActionsAutoHide) return;

      if (myLastOverPoint == null) return;

      if (myLastOverPoint.x >= 0 && myLastOverPoint.x < getWidth() && myLastOverPoint.y > 0 && myLastOverPoint.y < getHeight()) {
        final TabLabel label = myInfo2Label.get(_findInfo(myLastOverPoint, true));
        if (label != null) {
          if (myCurrentOverLabel != null) {
            myCurrentOverLabel.toggleShowActions(false);
          }
          label.toggleShowActions(true);
          myCurrentOverLabel = label;
          return;
        }
      }

      if (myCurrentOverLabel != null) {
        myCurrentOverLabel.toggleShowActions(false);
        myCurrentOverLabel = null;
      }
    }
  }

  private void removeTimerUpdate() {
    if (myActionManager != null && myListenerAdded) {
      myActionManager.removeTimerListener(this);
      myListenerAdded = false;
    }
  }

  public ModalityState getModalityState() {
    return ModalityState.stateForComponent(this);
  }

  public void run() {
    updateTabActions(false);
  }

  public void updateTabActions(final boolean validateNow) {
    final Ref<Boolean> changed = new Ref<Boolean>(Boolean.FALSE);
    for (final TabInfo eachInfo : myInfo2Label.keySet()) {
      updateTab(new Runnable() {
        public void run() {
          final boolean changes = myInfo2Label.get(eachInfo).updateTabActions();
          changed.set(changed.get().booleanValue() || changes);
        }
      }, eachInfo);
    }

    if (changed.get().booleanValue()) {
      if (validateNow) {
        validate();
        paintImmediately(0, 0, getWidth(), getHeight());
      }
    }
  }

  private void showMorePopup(final MouseEvent e) {
    mySingleRowLayout.myMorePopup = new JPopupMenu();
    for (final TabInfo each : myVisibleInfos) {
      final JCheckBoxMenuItem item = new JCheckBoxMenuItem(each.getText());
      mySingleRowLayout.myMorePopup.add(item);
      if (getSelectedInfo() == each) {
        item.setSelected(true);
      }
      item.addActionListener(new ActionListener() {
        public void actionPerformed(final ActionEvent e) {
          select(each, true);
        }
      });
    }

    mySingleRowLayout.myMorePopup.addPopupMenuListener(new PopupMenuListener() {
      public void popupMenuWillBecomeVisible(final PopupMenuEvent e) {
      }

      public void popupMenuWillBecomeInvisible(final PopupMenuEvent e) {
        mySingleRowLayout.myMorePopup = null;
      }

      public void popupMenuCanceled(final PopupMenuEvent e) {
        mySingleRowLayout.myMorePopup = null;
      }
    });

    mySingleRowLayout.myMorePopup.show(this, e.getX(), e.getY());
  }


  private JComponent getToFocus() {
    final TabInfo info = getSelectedInfo();

    if (info == null) return null;

    JComponent toFocus = null;

    if (isRequestFocusOnLastFocusedComponent() && info.getLastFocusOwner() != null && !isMyChildIsFocusedNow()) {
      toFocus = info.getLastFocusOwner();
    }

    if (toFocus == null && (info == null || info.getPreferredFocusableComponent() == null)) {
      return null;
    }


    if (toFocus == null) {
      toFocus = info.getPreferredFocusableComponent();
      final JComponent policyToFocus = myFocusManager != null ? myFocusManager.getFocusTargetFor(toFocus) : null;
      if (policyToFocus != null) {
        toFocus = policyToFocus;
      }
    }

    return toFocus;
  }

  public void requestFocus() {
    final JComponent toFocus = getToFocus();
    if (toFocus != null) {
      toFocus.requestFocus();
    }
    else {
      super.requestFocus();
    }
  }

  public boolean requestFocusInWindow() {
    final JComponent toFocus = getToFocus();
    if (toFocus != null) {
      return toFocus.requestFocusInWindow();
    }
    else {
      return super.requestFocusInWindow();
    }
  }

  private JBTabsImpl findTabs(Component c) {
    Component eachParent = c;
    while (eachParent != null) {
      if (eachParent instanceof JBTabsImpl) {
        return (JBTabsImpl)eachParent;
      }
      eachParent = eachParent.getParent();
    }

    return null;
  }


  @NotNull
  public TabInfo addTab(TabInfo info, int index) {
    if (getTabs().contains(info)) {
      return getTabs().get(getTabs().indexOf(info));
    }

    info.getChangeSupport().addPropertyChangeListener(this);
    final TabLabel label = new TabLabel(this, info);
    myInfo2Label.put(info, label);

    if (index < 0) {
      myVisibleInfos.add(info);
    }
    else if (index > myVisibleInfos.size() - 1) {
      myVisibleInfos.add(info);
    }
    else {
      myVisibleInfos.add(index, info);
    }

    myAllTabs = null;


    updateText(info);
    updateIcon(info);
    updateSideComponent(info);
    updateTabActions(info);

    add(label);

    adjust(info);

    updateAll(false, false);

    if (info.isHidden()) {
      updateHiding();
    }

    return info;
  }


  @NotNull
  public TabInfo addTab(TabInfo info) {
    return addTab(info, -1);
  }

  public ActionGroup getPopupGroup() {
    return myPopupGroup != null ? myPopupGroup.get() : null;
  }

  public String getPopupPlace() {
    return myPopupPlace;
  }

  public JBTabs setPopupGroup(@NotNull final ActionGroup popupGroup, @NotNull String place, final boolean addNavigationGroup) {
    return setPopupGroup(new Getter<ActionGroup>() {
      public ActionGroup get() {
        return popupGroup;
      }
    }, place, addNavigationGroup);
  }

  public JBTabs setPopupGroup(@NotNull final Getter<ActionGroup> popupGroup,
                              @NotNull final String place,
                              final boolean addNavigationGroup) {
    myPopupGroup = popupGroup;
    myPopupPlace = place;
    myAddNavigationGroup = addNavigationGroup;
    return this;
  }

  private void updateAll(final boolean forcedRelayout, final boolean now) {
    mySelectedInfo = getSelectedInfo();
    updateContainer(forcedRelayout, now);
    removeDeferred();
    updateListeners();
    updateTabActions(false);
  }

  private boolean isMyChildIsFocusedNow() {
    final Component owner = getFocusOwner();
    if (owner == null) return false;


    if (mySelectedInfo != null) {
      if (!SwingUtilities.isDescendingFrom(owner, mySelectedInfo.getComponent())) return false;
    }

    return SwingUtilities.isDescendingFrom(owner, this);
  }

  @Nullable
  private JComponent getFocusOwner() {
    final Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    return (JComponent)(owner instanceof JComponent ? owner : null);
  }

  public ActionCallback select(@NotNull TabInfo info, boolean requestFocus) {
    return _setSelected(info, requestFocus);
  }

  private ActionCallback _setSelected(final TabInfo info, final boolean requestFocus) {
    if (mySelectedInfo != null && mySelectedInfo.equals(info)) {
      if (!requestFocus) {
        return new ActionCallback.Done();
      }
      else {
        return requestFocus(getToFocus());
      }
    }

    if (myRequestFocusOnLastFocusedComponent && mySelectedInfo != null) {
      if (isMyChildIsFocusedNow()) {
        mySelectedInfo.setLastFocusOwner(getFocusOwner());
      }
    }

    TabInfo oldInfo = mySelectedInfo;
    mySelectedInfo = info;
    final TabInfo newInfo = getSelectedInfo();

    if (oldInfo != newInfo) {
      for (TabsListener eachListener : myTabListeners) {
        eachListener.beforeSelectionChanged(oldInfo, newInfo);
      }
    }


    updateContainer(false, true);

    if (oldInfo != newInfo) {
      for (TabsListener eachListener : myTabListeners) {
        if (eachListener != null) {
          eachListener.selectionChanged(oldInfo, newInfo);
        }
      }
    }

    if (requestFocus) {
      final JComponent toFocus = getToFocus();
      if (myProject != null && toFocus != null) {
        final ActionCallback result = new ActionCallback();
        requestFocus(toFocus).doWhenProcessed(new Runnable() {
          public void run() {
            if (myDisposed) {
              result.setRejected();
            }
            else {
              removeDeferred().notifyWhenDone(result);
            }
          }
        });
        return result;
      }
      else {
        requestFocus();
        return removeDeferred();
      }
    }
    else {
      return removeDeferred();
    }
  }

  private ActionCallback requestFocus(final JComponent toFocus) {
    if (toFocus == null) return new ActionCallback.Done();

    return myFocusManager.requestFocus(new FocusCommand(toFocus) {
      public ActionCallback run() {
        toFocus.requestFocus();
        return new ActionCallback.Done();
      }
    }, true);
  }

  private ActionCallback removeDeferred() {
    final ActionCallback callback = new ActionCallback();

    final long executionRequest = ++myRemoveDefferredRequest;

    final Runnable onDone = new Runnable() {
      public void run() {
        if (myRemoveDefferredRequest == executionRequest) {
          removeDeferredNow();
        }

        callback.setDone();
      }
    };

    if (myFocusManager != null) {
      myFocusManager.doWhenFocusSettlesDown(onDone);
    } else {
      onDone.run();
    }

    return callback;
  }

  private void queueForRemove(Component c) {
    if (c instanceof JComponent) {
      addToDeferredRemove(c);
    } else {
      remove(c);
    }
  }

  private void unqueueFromRemove(Component c) {
    myDeferredToRemove.remove(c);
  }

  private void removeDeferredNow() {
    for (Component each : myDeferredToRemove.keySet()) {
      if (each != null && each.getParent() == this) {
        remove(each);
      }
    }
    myDeferredToRemove.clear();
  }

  private void printRemoveInfo(final Component each) {
    TabInfo removingInfo = null;
    final List<TabInfo> all = getTabs();
    for (TabInfo eachInfo : all) {
      if (eachInfo.getComponent() == each) {
        removingInfo = eachInfo;
        break;
      }
    }

    //System.out.println(" - removing " + (removingInfo != null ? " component for " + removingInfo : each));
  }

  @Nullable
  public void propertyChange(final PropertyChangeEvent evt) {
    final TabInfo tabInfo = (TabInfo)evt.getSource();
    if (TabInfo.ACTION_GROUP.equals(evt.getPropertyName())) {
      updateSideComponent(tabInfo);
      relayout(false, false);
    }
    else if (TabInfo.TEXT.equals(evt.getPropertyName())) {
      updateText(tabInfo);
    }
    else if (TabInfo.ICON.equals(evt.getPropertyName())) {
      updateIcon(tabInfo);
    }
    else if (TabInfo.ALERT_STATUS.equals(evt.getPropertyName())) {
      boolean start = ((Boolean)evt.getNewValue()).booleanValue();
      updateAttraction(tabInfo, start);
    }
    else if (TabInfo.TAB_ACTION_GROUP.equals(evt.getPropertyName())) {
      updateTabActions(tabInfo);
      relayout(false, false);
    }
    else if (TabInfo.HIDDEN.equals(evt.getPropertyName())) {
      updateHiding();
      relayout(false, false);
    }
  }

  private void updateHiding() {
    boolean update = false;

    Iterator<TabInfo> visible = myVisibleInfos.iterator();
    while (visible.hasNext()) {
      TabInfo each = visible.next();
      if (each.isHidden() && !myHiddenInfos.contains(each)) {
        myHiddenInfos.add(each);
        visible.remove();
        update = true;
      }
    }


    Iterator<TabInfo> hidden = myHiddenInfos.iterator();
    while (hidden.hasNext()) {
      TabInfo each = hidden.next();
      if (!each.isHidden() && myHiddenInfos.contains(each)) {
        myVisibleInfos.add(each);
        hidden.remove();
        update = true;
      }
    }


    if (update) {
      myAllTabs = null;
      if (mySelectedInfo != null && myHiddenInfos.contains(mySelectedInfo)) {
        mySelectedInfo = getToSelectOnRemoveOf(mySelectedInfo);
      }
      updateAll(true, false);
    }
  }

  private void updateIcon(final TabInfo tabInfo) {
    updateTab(new Runnable() {
      public void run() {
        myInfo2Label.get(tabInfo).setIcon(tabInfo.getIcon());
      }
    }, tabInfo);
  }

  private void updateTab(Runnable update, TabInfo info) {
    final TabLabel label = myInfo2Label.get(info);
    final Dimension before = label.getPreferredSize();
    update.run();
    if (label.getRootPane() != null) {
      final Dimension after = label.getPreferredSize();
      if (after.equals(before)) {
        label.repaint();
      } else {
        revalidateAndRepaint(false);
      }
    }
  }

  void revalidateAndRepaint(final boolean layoutNow) {

    if (myVisibleInfos.size() == 0) {
      setOpaque(false);
      final Component nonOpaque = UIUtil.findUltimateParent(this);
      if (nonOpaque != null && getParent() != null) {
        final Rectangle toRepaint = SwingUtilities.convertRectangle(getParent(), getBounds(), nonOpaque);
        nonOpaque.repaint(toRepaint.x, toRepaint.y, toRepaint.width, toRepaint.height);
      }
    }
    else {
      setOpaque(true);
    }

    if (layoutNow) {
      validate();
    }
    else {
      revalidate();
    }

    repaint();
  }


  private void updateAttraction(final TabInfo tabInfo, boolean start) {
    if (start) {
      myAttractions.add(tabInfo);
    }
    else {
      myAttractions.remove(tabInfo);
      tabInfo.setBlinkCount(0);
    }

    if (start && !myAnimator.isRunning()) {
      myAnimator.resume();
    }
    else if (!start && myAttractions.size() == 0) {
      myAnimator.suspend();
      repaintAttractions();
    }
  }

  private void updateText(final TabInfo tabInfo) {
    updateTab(new Runnable() {
      public void run() {
        final TabLabel label = myInfo2Label.get(tabInfo);
        label.setText(tabInfo.getColoredText());
        label.setToolTipText(tabInfo.getTooltipText());
      }
    }, tabInfo);
  }

  private void updateSideComponent(final TabInfo tabInfo) {
    final JComponent old = myInfo2Toolbar.get(tabInfo);
    if (old != null) {
      remove(old);
    }
    final JComponent toolbar = createToolbarComponent(tabInfo);
    if (toolbar != null) {
      myInfo2Toolbar.put(tabInfo, toolbar);
      add(toolbar);
    }
  }

  private void updateTabActions(final TabInfo info) {
    myInfo2Label.get(info).setTabActions(info.getTabLabelActions());
  }

  @Nullable
  public TabInfo getSelectedInfo() {
    if (!myVisibleInfos.contains(mySelectedInfo)) {
      mySelectedInfo = null;
    }
    return mySelectedInfo != null ? mySelectedInfo : (myVisibleInfos.size() > 0 ? myVisibleInfos.get(0) : null);
  }

  @Nullable
  private TabInfo getToSelectOnRemoveOf(TabInfo info) {
    if (!myVisibleInfos.contains(info)) return null;
    if (mySelectedInfo != info) return null;

    if (myVisibleInfos.size() == 1) return null;

    int index = myVisibleInfos.indexOf(info);
    if (index > 0) return myVisibleInfos.get(index - 1);
    if (index < myVisibleInfos.size() - 1) return myVisibleInfos.get(index + 1);

    return null;
  }

  protected JComponent createToolbarComponent(final TabInfo tabInfo) {
    return new Toolbar(this, tabInfo);
  }

  @NotNull
  public TabInfo getTabAt(final int tabIndex) {
    return getTabs().get(tabIndex);
  }

  @NotNull
  public List<TabInfo> getTabs() {
    if (myAllTabs != null) return myAllTabs;

    ArrayList<TabInfo> result = new ArrayList<TabInfo>();
    result.addAll(myVisibleInfos);
    result.addAll(myHiddenInfos);

    myAllTabs = result;

    return result;
  }

  public TabInfo getTargetInfo() {
    return myPopupInfo != null ? myPopupInfo : getSelectedInfo();
  }

  public void popupMenuWillBecomeVisible(final PopupMenuEvent e) {
  }

  public void popupMenuWillBecomeInvisible(final PopupMenuEvent e) {
    resetPopup();
  }

  public void popupMenuCanceled(final PopupMenuEvent e) {
    resetPopup();
  }

  private void resetPopup() {
//todo [kirillk] dirty hack, should rely on ActionManager to understand that menu item was either chosen on or cancelled
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        myPopupInfo = null;
      }
    });
  }

  public void setPaintBlocked(boolean blocked, final boolean takeSnapshot) {
    if (blocked && !myPaintBlocked) {
      if (takeSnapshot) {
        myImage = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = myImage.createGraphics();
        super.paint(g);
        g.dispose();
      }
    }

    myPaintBlocked = blocked;

    if (!myPaintBlocked) {
      if (myImage != null) {
        myImage.flush();
      }

      myImage = null;
      repaint();
    }
  }


  private void addToDeferredRemove(final Component c) {
    if (!myDeferredToRemove.containsKey(c)) {
      myDeferredToRemove.put(c, c);
    }
  }

  public boolean isToDrawBorderIfTabsHidden() {
    return myToDrawBorderIfTabsHidden;
  }

  @NotNull
  public JBTabsPresentation setToDrawBorderIfTabsHidden(final boolean toDrawBorderIfTabsHidden) {
    myToDrawBorderIfTabsHidden = toDrawBorderIfTabsHidden;
    return this;
  }

  @NotNull
  public JBTabs getJBTabs() {
    return this;
  }

  public static class Toolbar extends JPanel {
    private JBTabsImpl myTabs;

    public Toolbar(JBTabsImpl tabs, TabInfo info) {
      myTabs = tabs;

      setLayout(new BorderLayout());

      final ActionGroup group = info.getGroup();
      final JComponent side = info.getSideComponent();

      if (group != null && myTabs.myActionManager != null) {
        final String place = info.getPlace();
        ActionToolbar toolbar =
            myTabs.myActionManager.createActionToolbar(place != null ? place : ActionPlaces.UNKNOWN, group, myTabs.myHorizontalSide);
        toolbar.setTargetComponent(info.getActionsContextComponent());
        final JComponent actionToolbar = toolbar.getComponent();
        add(actionToolbar, BorderLayout.CENTER);
      }

      if (side != null) {
        if (group != null) {
          add(side, BorderLayout.EAST);
        }
        else {
          add(side, BorderLayout.CENTER);
        }
      }
    }
  }


  public void doLayout() {
    try {
      final Max max = computeMaxSize();

      if (myPosition == JBTabsPosition.top || myPosition == JBTabsPosition.bottom) {
        myHeaderFitSize =
          new Dimension(getSize().width, myHorizontalSide ? Math.max(max.myLabel.height, max.myToolbar.height) : max.myLabel.height);
      } else {
        myHeaderFitSize =
          new Dimension(max.myLabel.width + (myHorizontalSide ? 0 : max.myToolbar.width), getSize().height);
      }

      final Collection<TabLabel> labels = myInfo2Label.values();
      for (Iterator<TabLabel> iterator = labels.iterator(); iterator.hasNext();) {
        TabLabel each = iterator.next();
        each.setTabActionsAutoHide(myTabLabelActionsAutoHide);
      }


      if (isSingleRow()) {
        myLastLayoutPass = mySingleRowLayout.layoutSingleRow();
        myTableLayout.myLastTableLayout = null;
      }
      else {
        myLastLayoutPass = myTableLayout.layoutTable();
        mySingleRowLayout.myLastSingRowLayout = null;
      }

      if (isStealthModeEffective() && !isHideTabs()) {
        final TabLabel label = myInfo2Label.get(getSelectedInfo());
        final Rectangle bounds = label.getBounds();
        final Insets insets = getLayoutInsets();
        layout(label, bounds.x, bounds.y, getWidth() - insets.right - insets.left, bounds.height);
      }

      myTabActionsAutoHideListener.processMouseOver();
    }
    finally {
      myForcedRelayout = false;
    }

    applyResetComponents();
  }

  public void layoutComp(int componentX, int componentY, final JComponent comp) {
    final Insets insets = getLayoutInsets();

    final Insets border = isHideTabs() ? new Insets(0, 0, 0, 0) : (Insets)myBorderSize.clone();
    if (isStealthModeEffective() || isHideTabs()) {
      border.top = getBorder(-1);
      border.bottom = getBorder(-1);
      border.left = getBorder(-1);
      border.right = getBorder(-1);
    }

    final Insets inner = getInnerInsets();
    border.top += inner.top;
    border.bottom += inner.bottom;
    border.left += inner.left;
    border.right += inner.right;

    layout(comp, insets.left + componentX + border.left, componentY + border.top,
           getWidth() - insets.left - insets.right - componentX - border.left - border.right,
           getHeight() - insets.bottom - componentY - border.top - border.bottom);
  }


  public JBTabsPresentation setInnerInsets(final Insets innerInsets) {
    myInnerInsets = innerInsets;
    return this;
  }

  public Insets getInnerInsets() {
    return myInnerInsets;
  }

  public Insets getLayoutInsets() {
    Insets insets = getInsets();
    if (insets == null) {
      insets = new Insets(0, 0, 0, 0);
    }
    return insets;
  }

  private int fixInset(int inset, int addin) {
    return inset + addin;
  }


  public int getToolbarInset() {
    return getArcSize() + 1;
  }

  public void resetLayout(boolean resetLabels) {
    if (resetLabels) {
      mySingleRowLayout.myLeftGhost.reset();
      mySingleRowLayout.myRightGhost.reset();
    }

    for (TabInfo each : myVisibleInfos) {
      reset(each, resetLabels);
    }

    for (TabInfo each : myHiddenInfos) {
      reset(each, resetLabels);
    }

    for (Component eachDeferred : myDeferredToRemove.keySet()) {
      resetLayout((JComponent)eachDeferred);
    }
  }

  private void reset(final TabInfo each, final boolean resetLabels) {
    final JComponent c = each.getComponent();
    if (c != null) {
      resetLayout(c);
    }

    final JComponent toolbar = myInfo2Toolbar.get(each);
    if (toolbar != null) {
      resetLayout(toolbar);
    }

    if (resetLabels) {
      resetLayout(myInfo2Label.get(each));
    }
  }


  private int getArcSize() {
    return 4;
  }

  public int getGhostTabLength() {
    return 15;
  }


  protected void paintComponent(final Graphics g) {
    super.paintComponent(g);

    if (myVisibleInfos.size() == 0) return;

    final GraphicsConfig config = new GraphicsConfig(g);
    config.setAntialiasing(true);
    
    Graphics2D g2d = (Graphics2D)g;


    g.setColor(getBackground());
    final Rectangle clip = g.getClipBounds();
    g.fillRect(clip.x, clip.y, clip.width, clip.height);

    int arc = getArcSize();

    final Color topBlickColor = getTopBlickColor();
    final Color rightBlockColor = getRightBlockColor();
    final Color boundsColor = getBoundsColor();

    Insets insets = getLayoutInsets();

    final TabInfo selected = getSelectedInfo();

    final int selectionTabVShift = getSelectionTabVShift();
    int curveArc = 2;

    boolean leftGhostExists = isSingleRow();
    boolean rightGhostExists = isSingleRow();

    if (myTestMode) {
      g.setColor(Color.red);
      for (TabInfo each : myVisibleInfos) {
        final TabLabel label = myInfo2Label.get(each);
        final Rectangle bounds = label.getBounds();
        g.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
      }
    }


    if (!isStealthModeEffective() && !isHideTabs()) {
      if (isSingleRow() && mySingleRowLayout.myLastSingRowLayout.lastGhostVisible) {
        int topX = mySingleRowLayout.myLastSingRowLayout.lastGhost.x - arc;
        int topY = mySingleRowLayout.myLastSingRowLayout.lastGhost.y + selectionTabVShift;
        int bottomX = (int)(mySingleRowLayout.myLastSingRowLayout.lastGhost.getMaxX() - curveArc);
        int bottomY = (int)mySingleRowLayout.myLastSingRowLayout.lastGhost.getMaxY() + 1;

        final GeneralPath path = new GeneralPath();
        path.moveTo(topX, topY);
        path.lineTo(bottomX, topY);
        path.quadTo(bottomX - curveArc, topY + (bottomY - topY) / 4, bottomX, topY + (bottomY - topY) / 2);
        path.quadTo(bottomX + curveArc, bottomY - (bottomY - topY) / 4, bottomX, bottomY);
        path.lineTo(topX, bottomY);

        path.closePath();

        g2d.setColor(getBackground());
        g2d.fill(path);

        g2d.setColor(boundsColor);
        g2d.draw(path);

        g2d.setColor(topBlickColor);
        g2d.drawLine(topX, topY + 1, bottomX - curveArc, topY + 1);
      }


      paintNonSelectedTabs(g2d, leftGhostExists);

      if (isSingleRow() && mySingleRowLayout.myLastSingRowLayout.firstGhostVisible) {
        final GeneralPath path = new GeneralPath();

        int topX = mySingleRowLayout.myLastSingRowLayout.firstGhost.x + curveArc;
        int topY = mySingleRowLayout.myLastSingRowLayout.firstGhost.y + selectionTabVShift;
        int bottomX = (int)mySingleRowLayout.myLastSingRowLayout.firstGhost.getMaxX() + 1;
        int bottomY = (int)(mySingleRowLayout.myLastSingRowLayout.firstGhost.getMaxY() + 1);

        path.moveTo(topX, topY);

        final boolean isLeftFromSelection = mySingleRowLayout.myLastSingRowLayout.toLayout.indexOf(getSelectedInfo()) == 0;

        if (isLeftFromSelection) {
          path.lineTo(bottomX, topY);
        }
        else {
          path.lineTo(bottomX - arc, topY);
          path.quadTo(bottomX, topY, bottomX, topY + arc);
        }

        path.lineTo(bottomX, bottomY);
        path.lineTo(topX, bottomY);

        path.quadTo(topX - curveArc * 2 + 1, bottomY - (bottomY - topY) / 4, topX, bottomY - (bottomY - topY) / 2);

        path.quadTo(topX + curveArc - 1, topY + (bottomY - topY) / 4, topX, topY);

        path.closePath();

        g2d.setColor(getBackground());
        g2d.fill(path);

        g.setColor(boundsColor);
        g2d.draw(path);

        g.setColor(topBlickColor);
        g.drawLine(topX + 1, topY + 1, bottomX - arc, topY + 1);

        g.setColor(rightBlockColor);
        g2d.drawLine(bottomX - 1, topY + arc, bottomX - 1, bottomY - 1);
      }

    }

    if (selected == null) return;


    final TabLabel selectedLabel = myInfo2Label.get(selected);
    if (selectedLabel == null) return;

    Rectangle selectedTabBounds = selectedLabel.getBounds();


    final GeneralPath path = new GeneralPath();
    int bottomY = (int)selectedTabBounds.getMaxY() + 1;
    final int topY = selectedTabBounds.y;
    int leftX = selectedTabBounds.x;

    int rightX = selectedTabBounds.x + selectedTabBounds.width;

    path.moveTo(insets.left, bottomY);
    path.lineTo(leftX, bottomY);
    path.lineTo(leftX, topY + arc);
    path.quadTo(leftX, topY, leftX + arc, topY);

    int lastX = getWidth() - insets.right - 1;

    if (isStealthModeEffective()) {
      path.lineTo(lastX - arc, topY);
      path.quadTo(lastX, topY, lastX, topY + arc);
      path.lineTo(lastX, bottomY);
    }
    else {
      path.lineTo(rightX - arc, topY);
      path.quadTo(rightX, topY, rightX, topY + arc);
      if (myLastLayoutPass.hasCurveSpaceFor(selected)) {
        path.lineTo(rightX, bottomY - arc);
        path.quadTo(rightX, bottomY, rightX + arc, bottomY);
      }
      else {
        path.lineTo(rightX, bottomY);
      }
    }

    path.lineTo(lastX, bottomY);

    if (isStealthModeEffective()) {
      path.closePath();
    }

    final GeneralPath fillPath = (GeneralPath)path.clone();
    if (!isHideTabs()) {
      fillPath.lineTo(lastX, bottomY + 1);
      fillPath.lineTo(leftX, bottomY + 1);
      fillPath.closePath();
      g2d.setColor(getBackground());
      g2d.fill(fillPath);
    }


    final Color from;
    final Color to;
    final int alpha;
    int paintTopY = topY;
    int paintBottomY = bottomY;
    final boolean paintFocused = myPaintFocus && (myFocused || myActivePopup != null);
    Color bgPreFill = null;
    if (paintFocused) {
      if (getActiveTabFillIn() == null) {
        from = UIUtil.getFocusedFillColor();
        to = UIUtil.getFocusedFillColor();
      }
      else {
        bgPreFill = getActiveTabFillIn();
        alpha = 255;
        paintBottomY = topY + getArcSize() - 2;
        from = UIUtil.toAlpha(UIUtil.getFocusedFillColor(), alpha);
        to = UIUtil.toAlpha(getActiveTabFillIn(), alpha);
      }
    }
    else {
      if (isPaintFocus()) {
        if (getActiveTabFillIn() == null) {
          alpha = 150;
          from = UIUtil.toAlpha(UIUtil.getPanelBackgound().brighter(), alpha);
          to = UIUtil.toAlpha(UIUtil.getPanelBackgound(), alpha);
        }
        else {
          alpha = 255;
          from = UIUtil.toAlpha(getActiveTabFillIn(), alpha);
          to = UIUtil.toAlpha(getActiveTabFillIn(), alpha);
        }
      }
      else {
        alpha = 255;
        from = UIUtil.toAlpha(Color.white, alpha);
        to = UIUtil.toAlpha(Color.white, alpha);
      }
    }

    if (!isHideTabs()) {
      if (bgPreFill != null) {
        g2d.setColor(bgPreFill);
        g2d.fill(fillPath);
      }
      g2d.setPaint(new GradientPaint(selectedTabBounds.x, paintTopY, from, selectedTabBounds.x, paintBottomY, to));
      g2d.fill(fillPath);
    }

    Color borderColor = UIUtil.getBoundsColor(paintFocused);
    g2d.setColor(borderColor);

    if (!isHideTabs()) {
      g2d.draw(path);
    }

    if (isHideTabs()) {
      paintBorder(g2d, insets.left, insets.top, getWidth() - insets.left - insets.right, getHeight() - insets.bottom - insets.top,
                  borderColor, from, to, paintFocused);
    }
    else {
      paintBorder(g2d, insets.left, bottomY, getWidth() - insets.left - insets.right, getHeight() - bottomY - insets.bottom, borderColor,
                  from, to, paintFocused);
    }

    config.setAntialiasing(false);
    if (isSideComponentVertical()) {
      JComponent toolbarComp = myInfo2Toolbar.get(mySelectedInfo);
      if (toolbarComp != null) {
        Rectangle toolBounds = toolbarComp.getBounds();
        g2d.setColor(CaptionPanel.CNT_ACTIVE_COLOR);
        g.drawLine((int)toolBounds.getMaxX(), toolBounds.y, (int)toolBounds.getMaxX(), (int)toolBounds.getMaxY() - 1);
      }
    }

    config.restore();
  }

  private Color getBoundsColor() {
    return Color.gray;
  }

  private Color getRightBlockColor() {
    return Color.lightGray;
  }

  private Color getTopBlickColor() {
    return Color.white;
  }

  private void paintNonSelectedTabs(final Graphics2D g2d, final boolean leftGhostExists) {
    for (int eachRow = 0; eachRow < myLastLayoutPass.getRowCount(); eachRow++) {
      for (int eachColumn = myLastLayoutPass.getColumnCount(eachRow) - 1; eachColumn >= 0; eachColumn--) {
        final TabInfo each = myLastLayoutPass.getTabAt(eachRow, eachColumn);
        if (getSelectedInfo() == each) continue;
        paintTab(g2d, each, leftGhostExists);
      }
    }
  }

  private void paintTab(final Graphics2D g2d, final TabInfo each, final boolean leftGhostExists) {
    int tabIndex = myVisibleInfos.indexOf(each);

    final int arc = getArcSize();
    final Color topBlickColor = getTopBlickColor();
    final Color rightBlockColor = getRightBlockColor();
    final Color boundsColor = getBoundsColor();
    final TabInfo selected = getSelectedInfo();
    final int selectionTabVShift = getSelectionTabVShift();


    final TabLabel eachLabel = myInfo2Label.get(each);
    if (eachLabel.getBounds().width == 0) return;


    final TabInfo prev = myLastLayoutPass.getPreviousFor(myVisibleInfos.get(tabIndex));
    final TabInfo next = myLastLayoutPass.getNextFor(myVisibleInfos.get(tabIndex));

    final Rectangle eachBounds = eachLabel.getBounds();
    final GeneralPath path = new GeneralPath();

    boolean firstShowing = prev == null;
    if (!firstShowing && !leftGhostExists) {
      firstShowing = myInfo2Label.get(prev).getBounds().width == 0;
    }

    boolean lastShowing = next == null;
    if (!lastShowing) {
      lastShowing = myInfo2Label.get(next).getBounds().width == 0;
    }

    boolean leftFromSelection = selected != null && tabIndex == myVisibleInfos.indexOf(selected) - 1;


    int leftX = firstShowing ? eachBounds.x : eachBounds.x - arc - 1;
    int topY = eachBounds.y + selectionTabVShift;
    int rigthX = !lastShowing && leftFromSelection ? (int)eachBounds.getMaxX() + arc + 1 : (int)eachBounds.getMaxX();
    int bottomY = (int)eachBounds.getMaxY() + 1;

    path.moveTo(leftX, bottomY);
    path.lineTo(leftX, topY + arc);
    path.quadTo(leftX, topY, leftX + arc, topY);
    path.lineTo(rigthX - arc, topY);
    path.quadTo(rigthX, topY, rigthX, topY + arc);
    path.lineTo(rigthX, bottomY);

    if (!isSingleRow()) {
      final TablePassInfo info = myTableLayout.myLastTableLayout;
      if (!info.isInSelectionRow(each)) {
        path.lineTo(rigthX, bottomY + getArcSize());
        path.lineTo(leftX, bottomY + getArcSize());
        path.lineTo(leftX, bottomY);
      }
    }

    path.closePath();

    g2d.setColor(getBackground());
    g2d.fill(path);

    g2d.setColor(topBlickColor);
    g2d.drawLine(leftX + arc, topY + 1, rigthX - arc, topY + 1);

    g2d.setColor(rightBlockColor);
    g2d.drawLine(rigthX - 1, topY + arc - 1, rigthX - 1, bottomY);

    g2d.setColor(boundsColor);
    g2d.draw(path);
  }

  public int getSelectionTabVShift() {
    return 2;
  }

  private void paintBorder(Graphics2D g2d,
                           int x,
                           int y,
                           int width,
                           int height,
                           final Color borderColor,
                           final Color fillFrom,
                           final Color fillTo,
                           boolean isFocused) {
    int topY = y + 1;
    int bottomY = y + myBorderSize.top - 2;
    int middleY = topY + (bottomY - topY) / 2;

    if (myBorderSize.top > 0) {
      if (isHideTabs()) {
        if (isToDrawBorderIfTabsHidden()) {
          g2d.setColor(borderColor);
          g2d.drawLine(x, y, x + width - 1, y);
        }
      }
      else if (isStealthModeEffective()) {
        g2d.setColor(borderColor);
        g2d.drawLine(x, y - 1, x + width - 1, y - 1);
      }
      else if (getActiveTabFillIn() == null) {
        if (myBorderSize.top > 1) {
          g2d.setColor(Color.white);
          g2d.fillRect(x, topY, width, bottomY - topY);

          g2d.setColor(fillTo);
          g2d.fillRect(x, topY, width, middleY - topY);

          final Color relfectionStartColor =
              isFocused ? UIUtil.toAlpha(UIUtil.getListSelectionBackground().darker(), 125) : UIUtil.toAlpha(borderColor, 75);
          g2d.setPaint(new GradientPaint(x, middleY, relfectionStartColor, x, bottomY, UIUtil.toAlpha(Color.white, 255)));
          g2d.fillRect(x, middleY, width, bottomY - middleY);

          g2d.setColor(UIUtil.toAlpha(Color.white, 100));
          g2d.drawLine(x, topY, x + width - 1, topY);


          g2d.setColor(Color.lightGray);
          g2d.drawLine(x, bottomY, x + width - 1, bottomY);
        }
        else if (myBorderSize.top == 1) {
          g2d.setColor(borderColor);
          g2d.drawLine(x, y, x + width - 1, y);
        }
      }
      else {
        g2d.setColor(getActiveTabFillIn());
        g2d.fillRect(x, topY, width, myBorderSize.top);
        if (myBorderSize.top > 1) {
          g2d.setColor(borderColor);
          final int topLine = topY + myBorderSize.top - 2;
          g2d.drawLine(x, topLine, x + width - 1, topLine);
        }
      }
    }

    g2d.setColor(borderColor);
    g2d.fillRect(x, y + height - myBorderSize.bottom, width, myBorderSize.bottom);

    g2d.fillRect(x, y, myBorderSize.left, height);
    g2d.fillRect(x + width - myBorderSize.right, y, myBorderSize.right, height);
  }

  public boolean isStealthModeEffective() {
    return myStealthTabMode && getTabCount() == 1 && isSideComponentVertical();
  }


  private boolean isNavigationVisible() {
    if (myStealthTabMode && getTabCount() == 1) return false;
    return myVisibleInfos.size() > 0;
  }


  public void paint(final Graphics g) {
    Rectangle clip = g.getClipBounds();
    if (clip == null) {
      return;
    }

    //System.out.println("JBTabsImpl.paint clip=" + clip);

    if (myPaintBlocked) {
      if (myImage != null) {
        g.drawImage(myImage, 0, 0, getWidth(), getHeight(), null);
      }
      return;
    }

    super.paint(g);
  }

  protected void paintChildren(final Graphics g) {
    super.paintChildren(g);

    //if (isSingleRow() && myLastSingRowLayout != null) {
    //  final List<TabInfo> infos = myLastSingRowLayout.toLayout;
    //  for (int i = 1; i < infos.size(); i++) {
    //    final TabInfo each = infos.get(i);
    //    if (getSelectedInfo() != each && getSelectedInfo() != infos.get(i - 1)) {
    //      drawSeparator(g, each);
    //    }
    //  }
    //}
    //else if (!isSingleRow() && myLastTableLayout != null) {
    //  final List<TableRow> table = myLastTableLayout.table;
    //  for (TableRow eachRow : table) {
    //    final List<TabInfo> infos = eachRow.myColumns;
    //    for (int i = 1; i < infos.size(); i++) {
    //      final TabInfo each = infos.get(i);
    //      if (getSelectedInfo() != each && getSelectedInfo() != infos.get(i - 1)) {
    //        drawSeparator(g, each);
    //      }
    //    }
    //  }
    //}

    mySingleRowLayout.myMoreIcon.paintIcon(this, g);
  }

  private void drawSeparator(Graphics g, TabInfo info) {
    final TabLabel label = myInfo2Label.get(info);
    if (label == null) return;
    final Rectangle bounds = label.getBounds();

    final double height = bounds.height * 0.85d;
    final double delta = bounds.height - height;

    final int y1 = (int)(bounds.y + delta) + 1;
    final int x1 = bounds.x;
    final int y2 = (int)(bounds.y + bounds.height - delta);
    UIUtil.drawVDottedLine((Graphics2D)g, x1, y1, y2, getBackground(), Color.gray);
  }

  private Max computeMaxSize() {
    Max max = new Max();
    for (TabInfo eachInfo : myVisibleInfos) {
      final TabLabel label = myInfo2Label.get(eachInfo);
      max.myLabel.height = Math.max(max.myLabel.height, label.getPreferredSize().height);
      max.myLabel.width = Math.max(max.myLabel.width, label.getPreferredSize().width);
      final JComponent toolbar = myInfo2Toolbar.get(eachInfo);
      if (toolbar != null) {
        max.myToolbar.height = Math.max(max.myToolbar.height, toolbar.getPreferredSize().height);
        max.myToolbar.width = Math.max(max.myToolbar.width, toolbar.getPreferredSize().width);
      }
    }

    max.myToolbar.height++;

    return max;
  }

  @Nullable
  private JComponent getSelectedComponent() {
    final TabInfo selection = getSelectedInfo();
    if (selection != null) {
      final JComponent c = selection.getComponent();
      if (c != null && c.getParent() == this) return c;
    }

    return null;
  }

  public Dimension getMinimumSize() {
    final JComponent c = getSelectedComponent();
    return c != null ? c.getMinimumSize() : new Dimension(0, 0);
  }

  public Dimension getMaximumSize() {
    final JComponent c = getSelectedComponent();
    return c != null ? c.getMaximumSize() : super.getPreferredSize();
  }

  public Dimension getPreferredSize() {
    final JComponent c = getSelectedComponent();
    return c != null ? c.getPreferredSize() : super.getPreferredSize();
  }

  public int getTabCount() {
    return getTabs().size();
  }

  @NotNull
  public JBTabsPresentation getPresentation() {
    return this;
  }

  public ActionCallback removeTab(final JComponent component) {
    return removeTab(findInfo(component));
  }

  public ActionCallback removeTab(final TabInfo info) {
    return removeTab(info, null);
  }

  public ActionCallback removeTab(final TabInfo info, @Nullable TabInfo forcedSelectionTranfer) {
    if (info == null || !getTabs().contains(info)) return new ActionCallback.Done();

    final ActionCallback result = new ActionCallback();

    TabInfo toSelect;
    if (forcedSelectionTranfer == null) {
      toSelect = getToSelectOnRemoveOf(info);
    } else {
      assert myVisibleInfos.contains(forcedSelectionTranfer) : "Cannot find tab for selection transfer, tab=" + forcedSelectionTranfer;
      toSelect = forcedSelectionTranfer;
    }


    if (toSelect != null) {
      processRemove(info, false);
      _setSelected(toSelect, true).doWhenProcessed(new Runnable() {
        public void run() {
          removeDeferred().notifyWhenDone(result);
        }
      });
    }
    else {
      processRemove(info, true);
      removeDeferred().notifyWhenDone(result);
    }

    if (myVisibleInfos.size() == 0) {
      removeDeferredNow();
    }

    revalidateAndRepaint(true);

    return result;
  }

  private void processRemove(final TabInfo info, boolean forcedNow) {
    remove(myInfo2Label.get(info));
    final JComponent tb = myInfo2Toolbar.get(info);
    if (tb != null) {
      remove(tb);
    }

    JComponent tabComponent = info.getComponent();

    if (!isToDeferRemoveForLater(tabComponent) || forcedNow) {
      remove(tabComponent);
      tabComponent = null;
    }
    else {
      queueForRemove(tabComponent);
    }

    myVisibleInfos.remove(info);
    myHiddenInfos.remove(info);
    myInfo2Label.remove(info);
    myInfo2Toolbar.remove(info);
    myAllTabs = null;

    updateAll(false, false);
  }

  public TabInfo findInfo(Component component) {
    for (TabInfo each : getTabs()) {
      if (each.getComponent() == component) return each;
    }

    return null;
  }

  public TabInfo findInfo(String text) {
    if (text == null) return null;

    for (TabInfo each : getTabs()) {
      if (text.equals(each.getText())) return each;
    }

    return null;
  }

  public TabInfo findInfo(MouseEvent event) {
    return findInfo(event, false);
  }

  private TabInfo findInfo(final MouseEvent event, final boolean labelsOnly) {
    final Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), this);
    return _findInfo(point, labelsOnly);
  }

  public TabInfo findInfo(final Object object) {
    for (int i = 0; i < getTabCount(); i++) {
      final TabInfo each = getTabAt(i);
      final Object eachObject = each.getObject();
      if (eachObject != null && eachObject.equals(object)) return each;
    }
    return null;
  }

  public TabInfo findTabLabelBy(final Point point) {
    return _findInfo(point, true);
  }

  private TabInfo _findInfo(final Point point, boolean labelsOnly) {
    Component component = findComponentAt(point);
    if (component == null) return null;
    while (component != this || component != null) {
      if (component instanceof TabLabel) {
        return ((TabLabel)component).getInfo();
      }
      else if (!labelsOnly) {
        final TabInfo info = findInfo(component);
        if (info != null) return info;
      }
      if (component == null) break;
      component = component.getParent();
    }

    return null;
  }

  public void removeAllTabs() {
    for (TabInfo each : getTabs()) {
      removeTab(each);
    }
  }


  private class Max {
    Dimension myLabel = new Dimension();
    Dimension myToolbar = new Dimension();
  }

  @Nullable
  private void updateContainer(boolean forced, final boolean layoutNow) {
    for (TabInfo each : myVisibleInfos) {
      final JComponent eachComponent = each.getComponent();
      if (getSelectedInfo() == each && getSelectedInfo() != null) {
        unqueueFromRemove(eachComponent);

        final Container parent = eachComponent.getParent();
        if (parent != null && parent != this) {
          parent.remove(eachComponent);
        }

        if (eachComponent.getParent() == null) {
          add(eachComponent);
        }
      }
      else {
        if (eachComponent.getParent() == null) continue;
        if (isToDeferRemoveForLater(eachComponent)) {
          queueForRemove(eachComponent);
        }
        else {
          remove(eachComponent);
        }
      }
    }

    relayout(forced, layoutNow);
  }

  protected void addImpl(final Component comp, final Object constraints, final int index) {
    unqueueFromRemove(comp);

    if (comp instanceof TabLabel) {
      ((TabLabel)comp).apply(myUiDecorator.getDecoration());
    }

    super.addImpl(comp, constraints, index);
  }


  private boolean isToDeferRemoveForLater(JComponent c) {
    return c.getRootPane() != null;
  }

  private void relayout(boolean forced, final boolean layoutNow) {
    if (!myForcedRelayout) {
      myForcedRelayout = forced;
    }
    revalidateAndRepaint(layoutNow);
  }

  ActionManager getActionManager() {
    return myActionManager;
  }

  @NotNull
  public JBTabs addTabMouseMotionListener(@NotNull MouseMotionListener listener) {
    removeListeners();
    myTabMouseListeners.add(listener);
    addListeners();
    return this;
  }

  @NotNull
  public JBTabs addTabMouseListener(@NotNull MouseListener listener) {
    removeListeners();
    myTabMouseListeners.add(listener);
    addListeners();
    return this;
  }

  @NotNull
  public JComponent getComponent() {
    return this;
  }

  @NotNull
  public JBTabs removeTabMouseListener(@NotNull MouseListener listener) {
    removeListeners();
    myTabMouseListeners.remove(listener);
    addListeners();
    return this;
  }

  private void addListeners() {
    for (TabInfo eachInfo : myVisibleInfos) {
      final TabLabel label = myInfo2Label.get(eachInfo);
      for (EventListener eachListener : myTabMouseListeners) {
        if (eachListener instanceof MouseListener) {
          label.addMouseListener(((MouseListener)eachListener));
        }
        else if (eachListener instanceof MouseMotionListener) {
          label.addMouseMotionListener(((MouseMotionListener)eachListener));
        }
        else {
          assert false;
        }
      }
    }
  }

  private void removeListeners() {
    for (TabInfo eachInfo : myVisibleInfos) {
      final TabLabel label = myInfo2Label.get(eachInfo);
      for (EventListener eachListener : myTabMouseListeners) {
        if (eachListener instanceof MouseListener) {
          label.removeMouseListener(((MouseListener)eachListener));
        }
        else if (eachListener instanceof MouseMotionListener) {
          label.removeMouseMotionListener(((MouseMotionListener)eachListener));
        }
        else {
          assert false;
        }
      }
    }
  }

  private void updateListeners() {
    removeListeners();
    addListeners();
  }

  public JBTabs addListener(@NotNull TabsListener listener) {
    myTabListeners.add(listener);
    return this;
  }

  public JBTabs removeListener(@NotNull final TabsListener listener) {
    myTabListeners.remove(listener);
    return this;
  }

  protected void onPopup(final TabInfo popupInfo) {
  }

  public void setFocused(final boolean focused) {
    if (myFocused == focused) return;

    myFocused = focused;

    if (myPaintFocus) {
      repaint();
    }
  }

  public int getIndexOf(@Nullable final TabInfo tabInfo) {
    return myVisibleInfos.indexOf(tabInfo);
  }

  public boolean isHideTabs() {
    return myHideTabs;
  }

  public void setHideTabs(final boolean hideTabs) {
    if (isHideTabs() == hideTabs) return;

    myHideTabs = hideTabs;

    relayout(true, false);
  }

  public JBTabsPresentation setPaintBorder(int top, int left, int right, int bottom) {
    final Insets newBorder = new Insets(getBorder(top), getBorder(left), getBorder(bottom), getBorder(right));
    if (newBorder.equals(myBorderSize)) return this;

    myBorderSize = newBorder;

    revalidateAndRepaint(false);

    return this;
  }

  private static int getBorder(int size) {
    return size == -1 ? 1 : size;
  }

  public boolean isPaintFocus() {
    return myPaintFocus;
  }

  @NotNull
  public JBTabsPresentation setAdjustBorders(final boolean adjust) {
    myAdjustBorders = adjust;
    return this;
  }

  @NotNull
  public JBTabsPresentation setActiveTabFillIn(@Nullable final Color color) {
    if (!isChanged(myActiveTabFillIn, color)) return this;

    myActiveTabFillIn = color;
    revalidateAndRepaint(false);
    return this;
  }

  private boolean isChanged(Object oldObject, Object newObject) {
    if (oldObject == null && newObject == null) return false;
    return (oldObject != null && !oldObject.equals(newObject)) || (newObject != null && !newObject.equals(oldObject));
  }

  public JBTabsPresentation setTabLabelActionsAutoHide(final boolean autoHide) {
    if (myTabLabelActionsAutoHide != autoHide) {
      myTabLabelActionsAutoHide = autoHide;
      revalidateAndRepaint(false);
    }
    return this;
  }

  @Nullable
  public Color getActiveTabFillIn() {
    return myActiveTabFillIn;
  }

  public JBTabsPresentation setFocusCycle(final boolean root) {
    setFocusCycleRoot(root);
    return this;
  }


  public JBTabsPresentation setPaintFocus(final boolean paintFocus) {
    myPaintFocus = paintFocus;
    return this;
  }

  private static abstract class BaseNavigationAction extends AnAction {

    private ShadowAction myShadow;

    protected BaseNavigationAction(final String copyFromID, JComponent c) {
      myShadow = new ShadowAction(this, ActionManager.getInstance().getAction(copyFromID), c);
    }

    public final void update(final AnActionEvent e) {
      JBTabsImpl tabs = e.getData(NAVIGATION_ACTIONS_KEY);
      e.getPresentation().setVisible(tabs != null);
      if (tabs == null) return;

      final int selectedIndex = tabs.myVisibleInfos.indexOf(tabs.getSelectedInfo());
      final boolean enabled = tabs.myVisibleInfos.size() > 0 && selectedIndex >= 0;
      e.getPresentation().setEnabled(enabled);
      if (enabled) {
        _update(e, tabs, selectedIndex);
      }
    }

    protected abstract void _update(AnActionEvent e, final JBTabsImpl tabs, int selectedIndex);

    public final void actionPerformed(final AnActionEvent e) {
      JBTabsImpl tabs = e.getData(NAVIGATION_ACTIONS_KEY);
      if (tabs == null) return;

      final int index = tabs.myVisibleInfos.indexOf(tabs.getSelectedInfo());
      if (index == -1) return;
      _actionPerformed(e, tabs, index);
    }

    protected abstract void _actionPerformed(final AnActionEvent e, final JBTabsImpl tabs, final int selectedIndex);
  }

  private static class SelectNextAction extends BaseNavigationAction {

    public SelectNextAction(JComponent c) {
      super(IdeActions.ACTION_NEXT_TAB, c);
    }

    protected void _update(final AnActionEvent e, final JBTabsImpl tabs, int selectedIndex) {
      e.getPresentation().setEnabled(tabs.myVisibleInfos.size() > 0 && selectedIndex < tabs.myVisibleInfos.size() - 1);
    }

    protected void _actionPerformed(final AnActionEvent e, final JBTabsImpl tabs, final int selectedIndex) {
      tabs.select(tabs.myVisibleInfos.get(selectedIndex + 1), true);
    }
  }

  private static class SelectPreviousAction extends BaseNavigationAction {
    public SelectPreviousAction(JComponent c) {
      super(IdeActions.ACTION_PREVIOUS_TAB, c);
    }

    protected void _update(final AnActionEvent e, final JBTabsImpl tabs, int selectedIndex) {
      e.getPresentation().setEnabled(tabs.myVisibleInfos.size() > 0 && selectedIndex > 0);
    }

    protected void _actionPerformed(final AnActionEvent e, final JBTabsImpl tabs, final int selectedIndex) {
      tabs.select(tabs.myVisibleInfos.get(selectedIndex - 1), true);
    }
  }

  private void disposePopupListener() {
    if (myActivePopup != null) {
      myActivePopup.removePopupMenuListener(myPopupListener);
      myActivePopup = null;
    }
  }

  public JBTabsPresentation setStealthTabMode(final boolean stealthTabMode) {
    myStealthTabMode = stealthTabMode;

    relayout(true, false);

    return this;
  }

  public boolean isStealthTabMode() {
    return myStealthTabMode;
  }

  public JBTabsPresentation setSideComponentVertical(final boolean vertical) {
    myHorizontalSide = !vertical;

    for (TabInfo each : myVisibleInfos) {
      each.getChangeSupport().firePropertyChange(TabInfo.ACTION_GROUP, "new1", "new2");
    }


    relayout(true, false);

    return this;
  }

  public JBTabsPresentation setSingleRow(boolean singleRow) {
    myLayout = singleRow ? mySingleRowLayout : myTableLayout;

    relayout(true, false);

    return this;
  }

  public JBTabsPresentation setGhostsAlwaysVisible(final boolean visible) {
    myGhostsAlwaysVisible = visible;

    relayout(true, false);

    return this;
  }

  public boolean isGhostsAlwaysVisible() {
    return myGhostsAlwaysVisible;
  }

  public boolean isSingleRow() {
    return myLayout == mySingleRowLayout;
  }

  public boolean isSideComponentVertical() {
    return !myHorizontalSide;
  }

  public JBTabsPresentation setUiDecorator(UiDecorator decorator) {
    myUiDecorator = decorator == null ? ourDefaultDecorator : decorator;
    applyDecoration();
    return this;
  }

  protected void setUI(final ComponentUI newUI) {
    super.setUI(newUI);
    applyDecoration();
  }

  public void updateUI() {
    super.updateUI();
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        applyDecoration();

        revalidateAndRepaint(false);
      }
    });
  }

  private void applyDecoration() {
    if (myUiDecorator != null) {
      UiDecorator.UiDecoration uiDecoration = myUiDecorator.getDecoration();
      for (TabLabel each : myInfo2Label.values()) {
        each.apply(uiDecoration);
      }
    }


    for (TabInfo each : getTabs()) {
      adjust(each);
    }

    relayout(true, false);
  }

  private void adjust(final TabInfo each) {
    if (myAdjustBorders) {
      UIUtil.removeScrollBorder(each.getComponent());
    }
  }

  public void sortTabs(Comparator<TabInfo> comparator) {
    Collections.sort(myVisibleInfos, comparator);

    relayout(true, false);
  }

  public boolean isRequestFocusOnLastFocusedComponent() {
    return myRequestFocusOnLastFocusedComponent;
  }

  public JBTabsPresentation setRequestFocusOnLastFocusedComponent(final boolean requestFocusOnLastFocusedComponent) {
    myRequestFocusOnLastFocusedComponent = requestFocusOnLastFocusedComponent;
    return this;
  }


  @Nullable
  public Object getData(@NonNls final String dataId) {
    if (myDataProvider != null) {
      final Object value = myDataProvider.getData(dataId);
      if (value != null) return value;
    }

    if (!NAVIGATION_ACTIONS_KEY.getName().equals(dataId)) return null;
    return isNavigationVisible() ? this : null;
  }


  public DataProvider getDataProvider() {
    return myDataProvider;
  }

  public JBTabsImpl setDataProvider(@NotNull final DataProvider dataProvider) {
    myDataProvider = dataProvider;
    return this;
  }


  public boolean isSelectionClick(final MouseEvent e, boolean canBeQuick) {
    if (e.getClickCount() == 1 || canBeQuick) {
      if (!e.isPopupTrigger()) {
        return e.getButton() == MouseEvent.BUTTON1 && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown();
      }
    }

    return false;
  }


  private static class DefautDecorator implements UiDecorator {
    @NotNull
    public UiDecoration getDecoration() {
      return new UiDecoration(null, new Insets(2, 8, 2, 8));
    }
  }

  public static void layout(JComponent c, Rectangle bounds) {
    final Rectangle now = c.getBounds();
    if (!bounds.equals(now)) {
      c.setBounds(bounds);
    }
    c.putClientProperty(LAYOUT_DONE, Boolean.TRUE);
  }

  public static void layout(JComponent c, int x, int y, int width, int height) {
    layout(c, new Rectangle(x, y, width, height));
  }

  public static void resetLayout(JComponent c) {
    c.putClientProperty(LAYOUT_DONE, null);
  }

  private void applyResetComponents() {
    for (int i = 0; i < getComponentCount(); i++) {
      final Component each = getComponent(i);
      if (each instanceof JComponent) {
        final JComponent jc = (JComponent)each;
        final Object done = jc.getClientProperty(LAYOUT_DONE);
        if (!Boolean.TRUE.equals(done)) {
          layout(jc, new Rectangle(0, 0, 0, 0));
        }
      }
    }
  }


  public JBTabsPresentation setTabLabelActionsMouseDeadzone(final TimedDeadzone.Length length) {
    myTabActionsMouseDeadzone = length;
    final List<TabInfo> all = getTabs();
    for (TabInfo each : all) {
      final TabLabel eachLabel = myInfo2Label.get(each);
      eachLabel.updateTabActions();
    }
    return this;
  }

  public JBTabsPresentation setTabsPosition(final JBTabsPosition position) {
    myPosition = position;
    relayout(true, false);
    return this;
  }

  public JBTabsPosition getTabsPosition() {
    return myPosition;
  }

  public TimedDeadzone.Length getTabActionsMouseDeadzone() {
    return myTabActionsMouseDeadzone;
  }
}
