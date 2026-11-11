# Newspaper

Periodical Publishing Software, Stage 1: a desktop client-server application for preparing a printed issue, in which an Editor, a Journalist and a Layout Designer work on the same issue at the same time from their own computers (see the [Vision](https://github.com/pkrasnyanskii/newspaper/wiki/Vision)).

The current prototype is the first working part of it: a single-user JavaFX application that creates an issue, manages its articles and saves/opens `.issue` files. The server mode and the roles are not implemented yet.

## Build and run

Java 21 is required. The Gradle wrapper downloads the project's build dependencies.

```bash
./gradlew test
./gradlew run
```

The application creates an issue with its number, date, page format and title, manages articles with embedded PNG/JPEG images, and saves/opens local `.issue` files.

## Using the current application

1. Fill in **Publication title**, a positive **Issue number**, **Issue date**, and **Page format** (A4 or A3).
2. Click **New issue** to create an issue from those fields in memory.
3. Open **Articles** and click **New article**. Enter a title, author and body text. Only the title is required, so an unfinished article can be kept as a draft.
4. Optionally use **Add images...** to select PNG/JPEG files. Images are embedded in the issue; the original files are not needed after saving. Remove attachments with **Remove image** in the editor.
5. Click **Add article** to add it to the open issue. Select an article to preview it, then use **Edit article** (or double-click) and **Apply changes** to revise it. **Cancel** discards only that editor's draft. **Delete article** asks for confirmation.
6. Click **Save** (Ctrl+S on Windows/Linux) and choose a location. The `.issue` extension is added if necessary. This saves the issue details and all applied articles and images together.
7. Use **Save** again after edits, **Save as...** for another file, or **Open...** to load a saved issue and its articles. Earlier files without articles still open.

**Add article / Apply changes** updates the issue in memory; **Save** writes it to disk. An asterisk in the window title and **Unsaved changes** in the status bar mark pending changes. Closing the application or replacing an issue offers **Save / Discard / Cancel**. Autosave and crash recovery are not implemented yet.

Image limits: PNG/JPEG, up to 20 MB and 25 megapixels per image; 128 MB of unique image data per issue. The same image used in several articles is stored once. Issue JSON is limited to 20 MB. Saving is atomic: a failed save leaves the previous file intact.

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
