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

### Evernote

Evernote has notebooks grouped in stacks rather than nested folders, so a note's path is mapped onto them:
`Biology` is the notebook "Biology" (or, if a stack "Biology" exists, a notebook "Biology" in that stack),
`Math/Linear Algebra` is the notebook "Linear Algebra" in the stack "Math", and further segments stay in the
notebook name (`Math/Algebra/Groups` is the notebook "Algebra/Groups"). Notebook names are unique in an Evernote
account, so an existing notebook with the wanted name is reused. An empty path uses the default notebook. Notes are
written as ENML (headings, paragraphs, lists, quotes, code) and appended to when the title already exists.

**Evernote cannot render equations.** With the `LATEX` or `KATEX` formula output type, formulas are kept as raw TeX
source in monospace and `recognizeText` and `uploadNote` print a warning. Use `PURE_TEXT` with Evernote.

1. Store an Evernote authentication token (a personal developer token, or an OAuth access token) as a
   "Secret text" credential.
2. Select **Evernote** as the note provider, then the credential and the service (Evernote, Yinxiang Biji, or the
   developer sandbox).

API calls go through the Jenkins proxy configuration; short rate limits are waited out automatically.

### Adding a note provider

Note providers are a Jenkins extension point. Extend `io.ohmvir.plugins.jenkinsocr.notes.NoteProvider`, implement
`listFolderPaths` and `doUpload`, and register a `NoteProviderDescriptor` with `@Extension`. Parse the note with
`parse(note, listener)` to get a format-neutral `NoteDocument` (headings, paragraphs, lists, quotes, code, equations
and styled inline text) and render it into the application's native format. Declare whether the application renders
TeX formulas with `isFormulaRenderingSupported()`; users are warned automatically when it does not.

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
