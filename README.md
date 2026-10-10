# jenkinsocr

## Introduction

A Jenkins plugin providing a build workflow to scan handwritten notes into note-taking applications. A photo of
a note is uploaded as a build parameter, transcribed by an AI model (through the
[jenkins-ai-synapse](https://github.com/OhmV-IR/jenkins-ai-synapse) plugin), filed into a folder of your notes and
uploaded with formatting and formulas rendered natively by the note application.

## Getting started

1. Configure a model in jenkins-ai-synapse.
2. In **Manage Jenkins » System » Note OCR Settings**, choose the model, the formula output type and a note provider
   (see below).
3. Create a pipeline such as [example/photo-to-notion-katex/Jenkinsfile](example/photo-to-notion-katex/Jenkinsfile):

```groovy
pipeline {
    agent any
    parameters {
        imageParameter(name: 'NOTE_IMAGE')
    }
    stages {
        stage('Scan note') {
            steps {
                script {
                    def note = recognizeText(parameterName: 'NOTE_IMAGE')
                    def location = uploadNote(text: note.text, title: note.title, path: note.path)
                    echo "Note stored at ${location}"
                }
            }
        }
    }
}
```

### Steps

| Step | Description |
| --- | --- |
| `recognizeText(parameterName:)` | Transcribes the image parameter and returns `text`, `title` and `path`. The model is given the existing folders of the note provider so that it can file the note in one of them. |
| `uploadNote(text:, title:, path:, formulaOutputType:)` | Uploads the note to the configured note provider and returns its location (e.g. a URL). Missing folders are created; a note whose title already exists in its folder is appended to. `path` and `formulaOutputType` are optional. |
| `notionUpload(notionText:, pageTitle:, pagePath:)` | Deprecated: use `uploadNote`. Kept for existing pipelines and requires Notion to be the configured provider. |

### Formula output types

| Type | The model writes | Notes |
| --- | --- | --- |
| `LATEX` | A LaTeX document segment | Sections, lists, emphasis, quotes and verbatim blocks are converted to the note application's formatting; math is kept as TeX. TikZ pictures, figures and tables cannot be converted and are kept as LaTeX source, with a warning. |
| `KATEX` | Markdown with `$...$` / `$$...$$` math | |
| `PURE_TEXT` | Plain text with Unicode math | For note applications that cannot render equations. |

If the configured note provider cannot render equations and `LATEX` or `KATEX` is selected, `recognizeText` and
`uploadNote` print a warning recommending `PURE_TEXT` or a provider that renders equations.

## Note providers

### Notion

Notes are stored as pages below a root page; each folder of a note's path is a sub-page. Notes are written as native
Notion blocks (headings, paragraphs, nested lists, quotes, code, dividers) with inline and block equations, and are
split as needed to stay within Notion's API limits (2000 characters per text object, 1000 per equation, 100 blocks
per request).

1. Create an [internal integration](https://www.notion.so/my-integrations) and store its token as a "Secret text"
   credential.
2. Share the root page with the integration (page menu » Connections).
3. Select **Notion** as the note provider, then the credential and the root page's URL or ID.

Existing configurations from before note providers were introduced are migrated to the Notion provider
automatically.

### Obsidian

Notes are stored as Markdown files in a vault folder; each folder of a note's path is a folder of the vault and the
note is a `.md` file named after its title. Formulas are written as `$...$` and `$$...$$`, which Obsidian renders
with MathJax. LaTeX output is converted to Markdown, plain text is escaped so that Obsidian shows it literally, and
`\(...\)`/`\[...\]` formulas in KaTeX output are rewritten to the dollar delimiters Obsidian understands.

1. Select **Obsidian** as the note provider.
2. Enter the absolute path of the vault folder (or of a folder inside the vault). The folder must exist.
3. Choose the node whose file system holds the vault: the controller, or an agent, for example one running on the
   computer you use Obsidian on. Alternatively keep the vault in a synced location (Obsidian Sync, Git, Syncthing,
   a cloud drive) reachable from the controller.

Titles and folder names are made safe for every file system (characters such as `:`, `?`, `#` or `/` are replaced);
when that changes a title, the original is kept as an Obsidian alias. Hidden folders such as `.obsidian` are never
listed or written to.

### Adding a note provider

Note providers are a Jenkins extension point. Extend `io.ohmvir.plugins.jenkinsocr.notes.NoteProvider`, implement
`listFolderPaths` and `doUpload`, and register a `NoteProviderDescriptor` with `@Extension`. Parse the note with
`parse(note, listener)` to get a format-neutral `NoteDocument` (headings, paragraphs, lists, quotes, code, equations
and styled inline text) and render it into the application's native format. Declare whether the application renders
TeX formulas with `isFormulaRenderingSupported()`; users are warned automatically when it does not.

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
