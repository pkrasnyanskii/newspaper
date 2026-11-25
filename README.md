# Newspaper

Periodical Publishing Software, Stage 1: a desktop client-server application for preparing a printed issue, in which an Editor, a Journalist and a Layout Designer work on the same issue at the same time from their own computers (see the [Vision](https://github.com/pkrasnyanskii/newspaper/wiki/Vision)).

The current build is a single-user JavaFX editor. It supports the local workflows for cover setup (UC-1), articles (UC-2), table of contents (UC-3), text styles (UC-4), page layout (UC-5), advertisements (UC-6) and PDF export (UC-8). Creating, opening and saving `.issue` files are available locally. Server-side issue management and recovery (UC-7), role permissions, synchronization and edit locks (UC-9) are pending.

## Build and run

Java 21 is required. The Gradle wrapper downloads the project's build dependencies.

```bash
./gradlew test
./gradlew run
```

On Windows use `.\gradlew.bat test` and `.\gradlew.bat run`.

## Using the current application

1. Fill in **Publication title**, a positive **Issue number**, **Issue date**, and **Page format** (A4 or A3).
2. Click **New issue** and use **Choose cover image...** to embed a PNG or JPEG on the cover.
3. Open **Articles** and click **New article**. Enter a title, author and body text. Only the title is required, so an unfinished article can be kept as a draft.
4. Optionally use **Add images...** to select PNG/JPEG files. Images are embedded in the issue; the original files are not needed after saving. Remove attachments with **Remove image** in the editor.
5. Click **Add article** to add it to the open issue. Select an article to preview it, then use **Edit article** (or double-click) and **Apply changes** to revise it. **Cancel** discards only that editor's draft. **Delete article** asks for confirmation.
6. Select an article, click **Style text...**, and select the text to format. Choose a size from 6 to 96 pt and a regular or bold weight.
7. Open **Pages** and add a page. Set the columns and margins, save or apply a layout template, and place articles, advertisements or standalone images. Enter block positions and sizes in millimetres from the top left corner. The page preview shows the same layout used for PDF export.
8. Open **Contents** to see each article's current page number. An unplaced article shows `-`. Titles and page numbers update with the issue.
9. Click **Save** (Ctrl+S on Windows/Linux) to write the issue, or **Save as...** to write a new copy. Use **Open...** to load a saved issue. The editor can open version 1 `.issue` files and writes version 2.
10. With at least one page in the issue, choose **Export PDF...** to write the cover and the numbered pages in the selected issue format.

Applied edits update the issue in memory. **Save** writes it to disk. An asterisk in the window title and **Unsaved changes** in the status bar mark pending changes. Closing the application or replacing an issue offers **Save / Discard / Cancel**.

The editor accepts PNG and JPEG images up to 20 MB and 25 megapixels per image, with up to 128 MB of unique image data per issue. An image used in several places is stored once. Issue JSON is limited to 20 MB. Issue saves and PDF exports use atomic replacement to preserve the previous file if writing fails.

PDF export embeds Noto Sans fonts for Latin, Greek and Cyrillic text. Characters missing from the fonts appear as question marks. Adjust block dimensions in the page preview to fit all text in the printable layout.

The form fills the available window width. In a narrow window, the detail fields and article panels stack and the buttons wrap; in a short window, scroll to reach the remaining controls. The article editor is also resizable. After source changes, close and restart the application with `./gradlew run` to load the updated interface.

## Design model

`Periodical_Publishing_Software.vpp` is the Visual Paradigm project (Community Edition 18.1) with the UML diagrams for the [Vision](https://github.com/pkrasnyanskii/newspaper/wiki/Vision):

- Use Case Diagram
- Class Diagram
- Data Storage Model
- Deployment Diagram
- Sequence diagrams UC-1 through UC-9

The PNG exports of these diagrams and the user interface sketches are not in this repository: they are stored in the `diagrams/` folder of the project [wiki](https://github.com/pkrasnyanskii/newspaper/wiki), which is a separate git repository, and shown on the [Vision](https://github.com/pkrasnyanskii/newspaper/wiki/Vision) page. To get the files locally:

```bash
git clone https://github.com/pkrasnyanskii/newspaper.wiki.git wiki
```
