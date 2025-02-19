package org.asciidoc.intellij.ui;

import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.JBSplitter;
import org.asciidoc.intellij.AsciiDocBundle;
import org.asciidoc.intellij.settings.AsciiDocApplicationSettings;
import org.asciidoc.intellij.toolbar.AsciiDocToolbarPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.HashMap;
import java.util.Map;

public abstract class SplitFileEditor<E1 extends TextEditor, E2 extends FileEditor> extends UserDataHolderBase implements FileEditor {
  public static final Key<SplitFileEditor<?, ?>> PARENT_SPLIT_KEY = Key.create("parentSplit");

  private static final String MY_PROPORTION_KEY = "SplitFileEditor.Proportion";

  @NotNull
  private final E1 myMainEditor;
  @NotNull
  private final E2 mySecondEditor;
  @NotNull
  private final JComponent myComponent;
  @NotNull
  private SplitEditorLayout mySplitEditorLayout =
    AsciiDocApplicationSettings.getInstance().getAsciiDocPreviewSettings().getSplitEditorLayout();
  @NotNull
  private final MyListenersMultimap myListenersGenerator = new MyListenersMultimap();

  private boolean myVerticalSplitOption;
  private boolean myEditorFirst;
  private JBSplitter mySplitter;

  protected SplitFileEditor(@NotNull E1 mainEditor, @NotNull E2 secondEditor) {
    myMainEditor = mainEditor;
    mySecondEditor = secondEditor;

    myComponent = createComponent();

    myMainEditor.putUserData(PARENT_SPLIT_KEY, this);

    AsciiDocApplicationSettings.SettingsChangedListener settingsChangedListener =
      settings -> ApplicationManager.getApplication().invokeLater(() -> {
        triggerSplitOrientationChange(settings.getAsciiDocPreviewSettings().isVerticalSplit());
        triggerEditorFirstChange(settings.getAsciiDocPreviewSettings().isEditorFirst());
        triggerLayoutChange(settings.getAsciiDocPreviewSettings().getSplitEditorLayout(), false);
      });

    ApplicationManager.getApplication().getMessageBus().connect(this)
      .subscribe(AsciiDocApplicationSettings.SettingsChangedListener.TOPIC, settingsChangedListener);

  }

  private void triggerEditorFirstChange(boolean isEditorFirst) {
    if (myEditorFirst == isEditorFirst) {
      return;
    }

    myEditorFirst = isEditorFirst;

    mySplitter.swapComponents();
    myComponent.repaint();
  }

  @NotNull
  private JComponent createComponent() {
    myVerticalSplitOption = AsciiDocApplicationSettings.getInstance().getAsciiDocPreviewSettings().isVerticalSplit();
    myEditorFirst = AsciiDocApplicationSettings.getInstance().getAsciiDocPreviewSettings().isEditorFirst();
    mySplitter = new JBSplitter(!myVerticalSplitOption, 0.5f, 0.15f, 0.85f);
    mySplitter.setSplitterProportionKey(MY_PROPORTION_KEY);
    mySplitter.setFirstComponent(myEditorFirst ? myMainEditor.getComponent() : mySecondEditor.getComponent());
    mySplitter.setSecondComponent(myEditorFirst ? mySecondEditor.getComponent() : myMainEditor.getComponent());

    AsciiDocToolbarPanel myToolbarWrapper = new AsciiDocToolbarPanel(myMainEditor.getEditor(), mySplitter);
    adjustToolbarVisibility(myToolbarWrapper, UISettings.getInstance());
    ApplicationManager.getApplication().getMessageBus().connect(this)
      .subscribe(UISettingsListener.TOPIC, uiSettings -> adjustToolbarVisibility(myToolbarWrapper, uiSettings));

    final JPanel result = new JPanel(new BorderLayout());
    result.add(myToolbarWrapper, BorderLayout.NORTH);
    result.add(mySplitter, BorderLayout.CENTER);
    adjustEditorsVisibility();

    return result;
  }

  private void adjustToolbarVisibility(AsciiDocToolbarPanel myToolbarWrapper, UISettings uiSettings) {
    myToolbarWrapper.setVisible(!uiSettings.getPresentationMode());
  }

  public void triggerLayoutChange(boolean requestFocus) {
    final int oldValue = mySplitEditorLayout.ordinal();
    final int n = SplitEditorLayout.values().length;
    final int newValue = (oldValue + n - 1) % n;

    triggerLayoutChange(SplitEditorLayout.values()[newValue], requestFocus);
  }

  public void triggerLayoutChange(@NotNull SplitFileEditor.SplitEditorLayout newLayout, boolean requestFocus) {
    if (mySplitEditorLayout == newLayout) {
      return;
    }

    mySplitEditorLayout = newLayout;
    invalidateLayout();

    if (requestFocus) {
      final JComponent focusComponent = getPreferredFocusedComponent();
      if (focusComponent != null) {
        IdeFocusManager.findInstanceByComponent(focusComponent).requestFocus(focusComponent, true);
      }
    }

  }

  private void triggerSplitOrientationChange(boolean isVerticalSplit) {
    if (myVerticalSplitOption == isVerticalSplit) {
      return;
    }

    myVerticalSplitOption = isVerticalSplit;

    mySplitter.setOrientation(!myVerticalSplitOption);
    myComponent.repaint();
  }

  @NotNull
  public SplitEditorLayout getCurrentEditorLayout() {
    return mySplitEditorLayout;
  }

  private void invalidateLayout() {
    adjustEditorsVisibility();
    myComponent.repaint();
  }

  protected void adjustEditorsVisibility() {
    myMainEditor.getComponent().setVisible(mySplitEditorLayout.showEditor);
    mySecondEditor.getComponent().setVisible(mySplitEditorLayout.showPreview);
  }

  @NotNull
  public E1 getMainEditor() {
    return myMainEditor;
  }

  @NotNull
  public E2 getSecondEditor() {
    return mySecondEditor;
  }

  @NotNull
  @Override
  public JComponent getComponent() {
    return myComponent;
  }

  @Nullable
  @Override
  public JComponent getPreferredFocusedComponent() {
    if (myMainEditor.getComponent().isVisible()) {
      return myMainEditor.getPreferredFocusedComponent();
    } else {
      return mySecondEditor.getPreferredFocusedComponent();
    }
  }

  @NotNull
  @Override
  public FileEditorState getState(@NotNull FileEditorStateLevel level) {
    return new MyFileEditorState(mySplitEditorLayout.name(), myMainEditor.getState(level), mySecondEditor.getState(level));
  }

  @Override
  public void setState(@NotNull FileEditorState state) {
    if (state instanceof MyFileEditorState) {
      final MyFileEditorState compositeState = (MyFileEditorState) state;
      if (compositeState.getFirstState() != null) {
        myMainEditor.setState(compositeState.getFirstState());
      }
      if (compositeState.getSecondState() != null) {
        mySecondEditor.setState(compositeState.getSecondState());
      }
      if (compositeState.getSplitLayout() != null) {
        mySplitEditorLayout = SplitEditorLayout.valueOf(compositeState.getSplitLayout());
        invalidateLayout();
      }
    }
  }

  @Override
  public boolean isModified() {
    return myMainEditor.isModified() || mySecondEditor.isModified();
  }

  @Override
  public boolean isValid() {
    return myMainEditor.isValid() && mySecondEditor.isValid();
  }

  @Override
  public void selectNotify() {
    myMainEditor.selectNotify();
    mySecondEditor.selectNotify();
  }

  @Override
  public void deselectNotify() {
    myMainEditor.deselectNotify();
    mySecondEditor.deselectNotify();
  }

  @Override
  public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
    myMainEditor.addPropertyChangeListener(listener);
    mySecondEditor.addPropertyChangeListener(listener);

    final DoublingEventListenerDelegate delegate = myListenersGenerator.addListenerAndGetDelegate(listener);
    myMainEditor.addPropertyChangeListener(delegate);
    mySecondEditor.addPropertyChangeListener(delegate);
  }

  @Override
  public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
    myMainEditor.removePropertyChangeListener(listener);
    mySecondEditor.removePropertyChangeListener(listener);

    final DoublingEventListenerDelegate delegate = myListenersGenerator.removeListenerAndGetDelegate(listener);
    if (delegate != null) {
      myMainEditor.removePropertyChangeListener(delegate);
      mySecondEditor.removePropertyChangeListener(delegate);
    }
  }

  @Nullable
  @Override
  public BackgroundEditorHighlighter getBackgroundHighlighter() {
    return myMainEditor.getBackgroundHighlighter();
  }

  @Nullable
  @Override
  public FileEditorLocation getCurrentLocation() {
    return myMainEditor.getCurrentLocation();
  }

  @Nullable
  @Override
  public StructureViewBuilder getStructureViewBuilder() {
    return myMainEditor.getStructureViewBuilder();
  }

  @Override
  public void dispose() {
    Disposer.dispose(myMainEditor);
    Disposer.dispose(mySecondEditor);
  }

  @Nullable
  @Override
  public VirtualFile getFile() {
    return getMainEditor().getFile();
  }

  static class MyFileEditorState implements FileEditorState {
    @Nullable
    private final String mySplitLayout;
    @Nullable
    private final FileEditorState myFirstState;
    @Nullable
    private final FileEditorState mySecondState;

    MyFileEditorState(@Nullable String splitLayout, @Nullable FileEditorState firstState, @Nullable FileEditorState secondState) {
      mySplitLayout = splitLayout;
      myFirstState = firstState;
      mySecondState = secondState;
    }

    @Nullable
    public String getSplitLayout() {
      return mySplitLayout;
    }

    @Nullable
    public FileEditorState getFirstState() {
      return myFirstState;
    }

    @Nullable
    public FileEditorState getSecondState() {
      return mySecondState;
    }

    @Override
    public boolean canBeMergedWith(FileEditorState otherState, FileEditorStateLevel level) {
      return otherState instanceof MyFileEditorState
        && (myFirstState == null || myFirstState.canBeMergedWith(((MyFileEditorState) otherState).myFirstState, level))
        && (mySecondState == null || mySecondState.canBeMergedWith(((MyFileEditorState) otherState).mySecondState, level));
    }
  }

  private class DoublingEventListenerDelegate implements PropertyChangeListener {
    @NotNull
    private final PropertyChangeListener myDelegate;

    private DoublingEventListenerDelegate(@NotNull PropertyChangeListener delegate) {
      myDelegate = delegate;
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
      myDelegate.propertyChange(new PropertyChangeEvent(SplitFileEditor.this, evt.getPropertyName(), evt.getOldValue(), evt.getNewValue()));
    }
  }

  private class MyListenersMultimap {
    private final Map<PropertyChangeListener, Pair<Integer, DoublingEventListenerDelegate>> myMap = new HashMap<>();

    @NotNull
    public DoublingEventListenerDelegate addListenerAndGetDelegate(@NotNull PropertyChangeListener listener) {
      if (!myMap.containsKey(listener)) {
        myMap.put(listener, Pair.create(1, new DoublingEventListenerDelegate(listener)));
      } else {
        final Pair<Integer, DoublingEventListenerDelegate> oldPair = myMap.get(listener);
        myMap.put(listener, Pair.create(oldPair.getFirst() + 1, oldPair.getSecond()));
      }

      return myMap.get(listener).getSecond();
    }

    @Nullable
    public DoublingEventListenerDelegate removeListenerAndGetDelegate(@NotNull PropertyChangeListener listener) {
      final Pair<Integer, DoublingEventListenerDelegate> oldPair = myMap.get(listener);
      if (oldPair == null) {
        return null;
      }

      if (oldPair.getFirst() == 1) {
        myMap.remove(listener);
      } else {
        myMap.put(listener, Pair.create(oldPair.getFirst() - 1, oldPair.getSecond()));
      }
      return oldPair.getSecond();
    }
  }

  public enum SplitEditorLayout {
    FIRST(true, false, AsciiDocBundle.message("asciidoc.layout.editor.only")),
    SECOND(false, true, AsciiDocBundle.message("asciidoc.layout.preview.only")),
    SPLIT(true, true, AsciiDocBundle.message("asciidoc.layout.editor.and.preview"));

    @SuppressWarnings("checkstyle:visibilitymodifier")
    public final boolean showEditor;
    @SuppressWarnings("checkstyle:visibilitymodifier")
    public final boolean showPreview;
    @SuppressWarnings("checkstyle:visibilitymodifier")
    public final String presentationName;

    SplitEditorLayout(boolean showEditor, boolean showPreview, String presentationName) {
      this.showEditor = showEditor;
      this.showPreview = showPreview;
      this.presentationName = presentationName;
    }

    @Override
    public String toString() {
      return presentationName;
    }
  }
}
