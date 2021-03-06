package io.github.donkirkby.livepycharm;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorProvider;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.jetbrains.python.PythonFileType;
import org.jdom.Attribute;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

public class SplitFileEditorProvider implements AsyncFileEditorProvider, DumbAware {
    private static final String FIRST_EDITOR = "first_editor";
    private static final String SECOND_EDITOR = "second_editor";
    private static final String SPLIT_LAYOUT = "split_layout";

    @NotNull
    private final com.intellij.openapi.fileEditor.FileEditorProvider myFirstProvider;
    @NotNull
    private final com.intellij.openapi.fileEditor.FileEditorProvider mySecondProvider;

    @NotNull
    private final String myEditorTypeId;

    public SplitFileEditorProvider() {
        myFirstProvider = new PsiAwareTextEditorProvider();
        mySecondProvider = new PsiAwareTextEditorProvider();

        myEditorTypeId = "live-pycharm";
    }

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        FileType fileType = file.getFileType();
        return myFirstProvider.accept(project, file) &&
                mySecondProvider.accept(project, file) &&
                fileType == PythonFileType.INSTANCE;
    }

    @NotNull
    @Override
    public FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return createEditorAsync(project, file).build();
    }

    @NotNull
    @Override
    public String getEditorTypeId() {
        return myEditorTypeId;
    }

    @NotNull
    @Override
    public Builder createEditorAsync(
            @NotNull final Project project,
            @NotNull final VirtualFile file) {
        LightVirtualFile displayFile = new LightVirtualFile(
                file.getName(),
                FileTypes.PLAIN_TEXT,
                "created for " + file.getName() + "\n");
        FileDocumentManager documentManager = FileDocumentManager.getInstance();
        Document mainDocument = documentManager.getDocument(file);
        Document displayDocument = documentManager.getDocument(displayFile);
        final Builder firstBuilder =
                getBuilderFromEditorProvider(myFirstProvider, project, file);
        final Builder secondBuilder =
                getBuilderFromEditorProvider(mySecondProvider, project, displayFile);

        return new Builder() {
            private Editor mainEditor;
            private Editor displayEditor;

            @Override
            public FileEditor build() {
                Disposable disposable = () -> {};
                EditorFactory.getInstance().addEditorFactoryListener(
                        new EditorFactoryAdapter() {
                            @Override
                            public void editorCreated(@NotNull EditorFactoryEvent event) {
                                Editor editor = event.getEditor();
                                Document document = editor.getDocument();
                                if (document == mainDocument) {
                                    mainEditor = editor;
                                } else if (document == displayDocument) {
                                    displayEditor = editor;
                                }
                            }
                        },
                        disposable
                );
                FileEditor editor = createSplitEditor(
                        firstBuilder.build(),
                        secondBuilder.build(),
                        file,
                        displayDocument);
                Editor mainEditor = this.mainEditor;
                Editor displayEditor = this.displayEditor;
                if (mainEditor != null && displayEditor != null) {
                    mainEditor.getScrollingModel().addVisibleAreaListener(
                            e -> {
                                ScrollingModel mainScroll =
                                        mainEditor.getScrollingModel();
                                ScrollingModel displayScroll =
                                        displayEditor.getScrollingModel();
                                displayScroll.scrollVertically(
                                        mainScroll.getVerticalScrollOffset());
                            });
                }
                return editor;
            }
        };
    }

    @NotNull
    @Override
    public FileEditorState readState(@NotNull Element sourceElement, @NotNull Project project, @NotNull VirtualFile file) {
        Element child = sourceElement.getChild(FIRST_EDITOR);
        FileEditorState firstState = null;
        if (child != null) {
            firstState = myFirstProvider.readState(child, project, file);
        }
        child = sourceElement.getChild(SECOND_EDITOR);
        FileEditorState secondState = null;
        if (child != null) {
            secondState = mySecondProvider.readState(child, project, file);
        }

        final Attribute attribute = sourceElement.getAttribute(SPLIT_LAYOUT);

        final String layoutName;
        if (attribute != null) {
            layoutName = attribute.getValue();
        }
        else {
            layoutName = null;
        }

        return new SplitFileEditor.MyFileEditorState(layoutName, firstState, secondState);
    }

    @Override
    public void writeState(@NotNull FileEditorState state, @NotNull Project project, @NotNull Element targetElement) {
        if (!(state instanceof SplitFileEditor.MyFileEditorState)) {
            return;
        }
        final SplitFileEditor.MyFileEditorState compositeState = (SplitFileEditor.MyFileEditorState)state;

        Element child = new Element(FIRST_EDITOR);
        if (compositeState.getFirstState() != null) {
            myFirstProvider.writeState(compositeState.getFirstState(), project, child);
            targetElement.addContent(child);
        }

        child = new Element(SECOND_EDITOR);
        if (compositeState.getSecondState() != null) {
            mySecondProvider.writeState(compositeState.getSecondState(), project, child);
            targetElement.addContent(child);
        }

        if (compositeState.getSplitLayout() != null) {
            targetElement.setAttribute(SPLIT_LAYOUT, compositeState.getSplitLayout());
        }
    }

    private FileEditor createSplitEditor(
            @NotNull final FileEditor firstEditor,
            @NotNull FileEditor secondEditor,
            VirtualFile file,
            Document displayDocument) {
        return new SplitFileEditor(firstEditor, secondEditor, file, displayDocument);
    }

    @NotNull
    @Override
    public FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }

    @NotNull
    private static Builder getBuilderFromEditorProvider(@NotNull final com.intellij.openapi.fileEditor.FileEditorProvider provider,
                                                        @NotNull final Project project,
                                                        @NotNull final VirtualFile file) {
        if (provider instanceof AsyncFileEditorProvider) {
            return ((AsyncFileEditorProvider)provider).createEditorAsync(project, file);
        }
        else {
            return new Builder() {
                @Override
                public FileEditor build() {
                    return provider.createEditor(project, file);
                }
            };
        }
    }
}

