# Newspaper

Periodical Publishing Software, Stage 1: a Java 21 / JavaFX desktop application for preparing a printed issue. An Editor, a Journalist and a Layout Designer connect to one server and share the same issue. The full requirements and UML model are in the [Vision](https://github.com/pkrasnyanskii/newspaper/wiki/Vision).

## Build and run

Java 21 is required. The Gradle wrapper downloads the build dependencies.

```bash
./gradlew test
```

Start the server in one terminal:

```bash
./gradlew run --args="--server"
```

Start a client in another terminal; repeat for the other roles:

```bash
./gradlew run
```

Enter `http://localhost:7070` and choose **Editor**, **Journalist** or **Layout Designer**. On another computer, replace `localhost` with the server computer's LAN address. The server listens on port 7070 on all interfaces by default. Stage 1 uses role selection without accounts or passwords and is intended for a trusted local network.

Optional server settings:

```bash
./gradlew run --args="--server --host 127.0.0.1 --port 7071 --data-dir /path/to/newspaper-data"
```

The default data directory is `~/.local/share/periodical-publishing` on Linux and `%LOCALAPPDATA%\PeriodicalPublishing` on Windows. Named issue files are stored in its `issues` subdirectory. Only one server can use a data directory at a time.

To run several processes without keeping Gradle running for each one:

```bash
./gradlew installDist
./build/install/newspaper/bin/newspaper --server
./build/install/newspaper/bin/newspaper
```

The Windows launcher is `build\install\newspaper\bin\newspaper.bat`. An installer with a bundled Java runtime is not provided yet.

## Current implementation

- **Editor:** create a shared issue, edit its metadata and cover image, manage advertisements, manage server-side `.issue` files and export PDF locally.
- **Journalist:** create, edit and delete articles with titles, authors, plain text and embedded PNG/JPEG images.
- **Layout Designer:** apply font size and weight to selected article text, add and remove pages, set columns and margins, reuse templates and place blocks.
- **Contents and preview:** every role can read the automatically numbered table of contents and see the same page layout used for export.
- **Shared editing:** JSON over HTTP, server-side permissions, object versions, article edit locks and automatic polling every 2 seconds. Each client sends individual object changes; the server returns the accepted state.
- **Connection loss:** the client keeps its last displayed state read-only and reconnects automatically. An open article draft stays in its window. Unconfirmed changes are not replayed automatically. If the server restarted or the lock expired, copy any unsent changes you need before cancelling, then reopen the article and reapply them. Cancel discards the draft.
- **Images:** validation, upload/download by SHA-256 hash, and deduplication on the server and in saved files. Existing images are reused without uploading them again.
- **Recovery:** accepted changes are scheduled for recovery saving every second. The server restores `recovery.issue` on startup, including the last named file. Recovery is retained on shutdown so an unsaved issue is not discarded. A recovery write failure is shown in connected clients.

The three-second synchronization and five-second recovery limits from the Vision are targets. Automated tests cover recovery timing and small shared issues; large documents, slow networks and all target desktop platforms still need performance testing.

## Using the application

1. Connect as **Editor**, fill in the issue details, and choose **New issue**. Creating or opening an issue replaces the shared issue for every connected client; save the current issue first if it needs to be kept.
2. To update the cover and issue details, edit the fields or choose a PNG or JPEG cover image, then select **Apply details**. **Discard draft** reloads the server values.
3. Connect another client as **Journalist**. Open **Articles**, choose **New article**, and enter a title, author and body. Only the article title is required.
4. Optionally use **Add images...** to attach PNG/JPEG files. **Add article / Apply changes** sends the article to the server. **Cancel** discards that editor's draft.
5. Editing an existing article acquires its lock before opening the editor. Other clients see **Being edited by …**. The server refuses a competing editor with HTTP 409. Closing the editor releases the lock; a session silent for two minutes loses its locks.
6. Connect as **Layout Designer**. Choose **Style text...** on an article to set the font weight and a size from 6 to 96 pt. This uses the same article lock as content editing. Use **Pages** to add pages, set margins and columns, save or apply templates, and place blocks. Block dimensions and positions are in millimetres from the top-left corner.
7. As **Editor**, use **Pages** to add or edit advertisements with text, an image, or both. Use **Contents** to review article page numbers. An unplaced article appears with `-`.
8. As **Editor**, choose **Save on server...** and enter a file name. **Open on server...** lists files in the server's `issues` folder. The server opens version 1 and 2 files and writes version 2.
9. With at least one page, choose **Export PDF...** to save the accepted cover and pages to your computer. PDF export embeds Noto Sans for Latin, Greek and Cyrillic text. Characters missing from those fonts appear as question marks.

Server saves are atomic: a failed write leaves the previous file intact. Image limits are PNG/JPEG, 20 MB and 25 megapixels per image, with 128 MB of unique image data per issue. Issue JSON is limited to 20 MB. Pending image uploads share a bounded 128 MB server cache; saving and reopening an issue clears unused uploads.

## Code structure and protocol

- `shared`: domain models, roles and HTTP message types.
- `server`: authoritative issue state, permissions, versions, locks, image cache, `.issue` storage and recovery; HTTP endpoints use Javalin.
- `client`: JavaFX views and `IssueClient`; network operations and polling run outside the JavaFX thread.
- Root application: selects client mode or `--server` mode.

`POST /sessions` connects with a role. Subsequent requests carry the returned session id in `X-Newspaper-Session`. `GET /issue` loads the issue; `POST /changes` applies a versioned object change; `GET /changes?since=...&epoch=...` polls updates. The current implementation returns a bounded change summary and a complete metadata snapshot when the revision changed, with images fetched separately. A server epoch prevents stale commands from being accepted after a restart.

`POST /locks/{articleId}` and `DELETE /locks/{articleId}` acquire and release locks. `HEAD/PUT/GET /images/{sha256}` check, upload and download images. `GET /files` lists server files; `POST /files/save` and `POST /files/open` manage them as Editor. Refusals include 403 for role permissions, 409 for conflicts or locks, and 422 for invalid input.

Tests cover shared cover images, styles and placements, automatic contents, role refusals, version conflicts, edit locks, recovery, archive compatibility and PDF output.

## Design model

`Periodical_Publishing_Software.vpp` is the Visual Paradigm project (Community Edition 18.1) with the UML diagrams for the [Vision](https://github.com/pkrasnyanskii/newspaper/wiki/Vision):

- Use Case Diagram
- Class Diagram
- Data Storage Model
- Deployment Diagram
- Sequence diagrams UC-1 through UC-9

The PNG exports and UI sketches are stored in the separate [wiki repository](https://github.com/pkrasnyanskii/newspaper/wiki), under `diagrams/`, and shown on the Vision page. To get them locally:

```bash
git clone https://github.com/pkrasnyanskii/newspaper.wiki.git wiki
```
