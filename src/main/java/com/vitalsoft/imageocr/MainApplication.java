package com.vitalsoft.imageocr;

/*
    Copyright 2025 Abraham .M (github.com/abraham-ny), VitalSoft

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.lucene.store.Directory;

/**
 * MainApp - Swing-based image search UI + background indexer (incremental).
 *
 * Notes: - Configure tessdataPath variable below if needed, otherwise uses
 * "tessdata" in working dir. - Index storage root: ./storage/indexes/<folderId>
 */
public class MainApplication {

    // config
    private static final String STORAGE_ROOT = "storage";
    private static final String INDEXES_DIRNAME = "indexes";
    private static final String TESSDATA_PATH = "tessdata"; // change this if your tessdata is elsewhere
    //private static final String TESSDATA_PATH = "/usr/share/tesseract-ocr/5/tessdata"; // change this if your tessdata is elsewhere
    private static final String TESS_LANG = "eng";

    // supported image extensions
    private static final Set<String> IMG_EXT = Set.of("png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff", "webp");

    // UI
    private JFrame frame;
    private JTextField searchField;
    private JButton chooseDirBtn;
    private JLabel statusLabel;
    private JProgressBar progressBar;
    private JPanel gridPanel;
    private JScrollPane scrollPane;

    // current directory state
    private File currentDir;
    private IndexManager indexManager;

    // lucene search page size
    private static final int MAX_RESULTS = 2000;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new MainApplication().createAndShowGui();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void createAndShowGui() {
        frame = new JFrame("Offline Image Search");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 700);
        frame.setLocationRelativeTo(null);

        // Top action bar
        JPanel actionBar = new JPanel(new BorderLayout(8, 8));
        actionBar.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Left: dir chooser
        chooseDirBtn = new JButton("Choose Folder to Index");
        chooseDirBtn.addActionListener(e -> chooseFolder());
        actionBar.add(chooseDirBtn, BorderLayout.WEST);

        // Center: search field
        JPanel centerBox = new JPanel(new BorderLayout(6, 6));
        searchField = new JTextField();
        searchField.setToolTipText("Type search and press Enter to search OCRed text in images");
        searchField.addActionListener(e -> performSearch());
        centerBox.add(searchField, BorderLayout.CENTER);

        // right: small re-index button
        JButton reindexBtn = new JButton("Re-index folder");
        reindexBtn.addActionListener(e -> {
            if (currentDir != null) {
                startIndexing(currentDir, true);
            } else {
                JOptionPane.showMessageDialog(frame, "Choose a folder first.");
            }
        });
        JButton tessDirBtn = new JButton("Find Tess");
        tessDirBtn.addActionListener(e -> {

        });
        centerBox.add(reindexBtn, BorderLayout.EAST);

        actionBar.add(centerBox, BorderLayout.CENTER);

        frame.getContentPane().add(actionBar, BorderLayout.NORTH);

        // wide progress bar just below action bar
        JPanel progressPanel = new JPanel(new BorderLayout());
        progressPanel.setBorder(new EmptyBorder(0, 10, 10, 10));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        statusLabel = new JLabel("Ready");
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(statusLabel, BorderLayout.SOUTH);

        frame.getContentPane().add(progressPanel, BorderLayout.AFTER_LAST_LINE);

        // central grid for thumbnails
        gridPanel = new JPanel();
        gridPanel.setLayout(new WrapLayout(FlowLayout.LEFT, 8, 8)); // custom wrap layout included below
        scrollPane = new JScrollPane(gridPanel);
        frame.getContentPane().add(scrollPane, BorderLayout.CENTER);

        // bottom small help text
        JLabel hint = new JLabel("Single-click: quick preview (borderless). Double-click: open in system viewer.");
        hint.setBorder(new EmptyBorder(6, 10, 10, 10));
        frame.getContentPane().add(hint, BorderLayout.SOUTH);

        // ensure storage dirs exist
        File storageRoot = new File(STORAGE_ROOT);
        if (!storageRoot.exists()) {
            storageRoot.mkdirs();
        }
        File idxRoot = new File(storageRoot, INDEXES_DIRNAME);
        if (!idxRoot.exists()) {
            idxRoot.mkdirs();
        }

        indexManager = new IndexManager(storageRoot);

        frame.setVisible(true);
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int res = fc.showOpenDialog(frame);
        if (res == JFileChooser.APPROVE_OPTION) {
            File dir = fc.getSelectedFile();
            //TESSDATA_PATH = dir.getPath();
            statusLabel.setText("Selected: " + dir.getAbsolutePath());
            // if not indexed, start indexing, else show existing thumbnails and check for changes
            if (!indexManager.isIndexed(dir)) {
                startIndexing(dir, false);
            } else {
                // index exists -> do incremental update check and reindex new files automatically
                startIndexing(dir, false);
                // then load previews
                SwingUtilities.invokeLater(() -> loadAllFromIndex(dir));
            }
        }
    }

    private void findTessDir() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int res = fc.showOpenDialog(frame);
        if (res == JFileChooser.APPROVE_OPTION) {
            File dir = fc.getSelectedFile();
            currentDir = dir;
            statusLabel.setText("Selected: " + dir.getAbsolutePath());
            // if not indexed, start indexing, else show existing thumbnails and check for changes
            if (!indexManager.isIndexed(dir)) {
                startIndexing(dir, false);
            } else {
                // index exists -> do incremental update check and reindex new files automatically
                startIndexing(dir, false);
                // then load previews
                SwingUtilities.invokeLater(() -> loadAllFromIndex(dir));
            }
        }
    }

    private void startIndexing(File dir, boolean forceReindex) {
        // start background worker
        IndexWorker worker = new IndexWorker(dir, indexManager, forceReindex);
        // bind progress and status
        worker.addPropertyChangeListener(evt -> {
            switch (evt.getPropertyName()) {
                case "progress":
                    int v = (Integer) evt.getNewValue();
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(v);
                    break;
                case "state":
                    if (evt.getNewValue() == SwingWorker.StateValue.DONE) {
                        statusLabel.setText("Indexing finished: " + Instant.now());
                        progressBar.setValue(progressBar.getMaximum());
                        // load thumbnails
                        loadAllFromIndex(dir);
                    }
                    break;
                case "message":
                    statusLabel.setText((String) evt.getNewValue());
                    break;
            }
        });
        progressBar.setIndeterminate(true);
        statusLabel.setText("Starting indexing...");
        worker.execute();
    }

    private void performSearch() {
        String q = searchField.getText().trim();
        if (q.isEmpty()) {
            if (currentDir != null) {
                loadAllFromIndex(currentDir);
            } else {
                JOptionPane.showMessageDialog(frame, "Choose a folder first.");
            }
            return;
        }
        if (!indexManager.isIndexed(currentDir)) {
            JOptionPane.showMessageDialog(frame, "Folder not indexed yet. Index it first.");
            return;
        }
        try {
            List<String> hits = indexManager.search(currentDir, q, MAX_RESULTS);
            showSearchResults(hits);
            statusLabel.setText("Search returned " + hits.size() + " results for: " + q);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Search failed: " + ex.getMessage());
        }
    }

    private void loadAllFromIndex(File dir) {
        if (dir == null) {
            return;
        }
        try {
            List<String> hits = indexManager.search(dir, "*", MAX_RESULTS); // wildcard - returns many
            showSearchResults(hits);
            statusLabel.setText("Loaded " + hits.size() + " indexed images.");
        } catch (Exception e) {
            e.printStackTrace();
            statusLabel.setText("Failed to load index: " + e.getMessage());
        }
    }

    private void showSearchResults(List<String> imagePaths) {
        gridPanel.removeAll();
        if (imagePaths == null || imagePaths.isEmpty()) {
            JLabel none = new JLabel("No images found.");
            none.setBorder(new EmptyBorder(40, 40, 40, 40));
            gridPanel.add(none);
            gridPanel.revalidate();
            gridPanel.repaint();
            return;
        }

        for (String path : imagePaths) {
            try {
                File f = new File(path);
                if (!f.exists()) {
                    continue;
                }
                ImageIcon icon = new ImageIcon(path);
                Image scaled = icon.getImage().getScaledInstance(180, -1, Image.SCALE_SMOOTH);
                JLabel thumb = new JLabel(new ImageIcon(scaled));
                thumb.setBorder(BorderFactory.createLineBorder(Color.GRAY));
                thumb.setToolTipText(f.getName());
                thumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                thumb.addMouseListener(new MouseAdapter() {
                    private long lastClickTime = 0;

                    @Override
                    public void mouseClicked(MouseEvent e) {
                        long now = System.currentTimeMillis();
                        if (now - lastClickTime < 400) {
                            // double-click
                            try {
                                Desktop.getDesktop().open(f);
                            } catch (Exception ex) {
                                JOptionPane.showMessageDialog(frame, "Cannot open with system viewer: " + ex.getMessage());
                            }
                        } else {
                            // single click -> quick preview
                            showQuickPreview(f);
                        }
                        lastClickTime = now;
                    }
                });

                gridPanel.add(thumb);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        gridPanel.revalidate();
        gridPanel.repaint();
    }

    // quick preview: borderless resizable window with image + text (text fetched from index)
    private void showQuickPreview(File imageFile) {
        JDialog dlg = new JDialog(frame);
        dlg.setUndecorated(true);
        dlg.setLayout(new BorderLayout());
        dlg.setSize(900, 600);
        dlg.setLocationRelativeTo(frame);

        // main split: image left, text right
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.6);

        BufferedImage mImg = null;
        try {
            BufferedImage img = ImageIO.read(imageFile);
            mImg = img;
            //BufferedImage img = ImageIO.read(new File(imgPath));
            //String ocrText = indexManager.getTextForImage(imageFile.getPath().toString()); // stored during indexing
            //new ImageViewerWindow(img, ocrText);

            ImageIcon icon = new ImageIcon(img);
            JLabel imageLabel = new JLabel();
            imageLabel.setIcon(new ImageIcon(icon.getImage().getScaledInstance(-1, 560, Image.SCALE_SMOOTH)));
            JScrollPane imgScroll = new JScrollPane(imageLabel);
            split.setLeftComponent(imgScroll);

        } catch (IOException ioe) {
            split.setLeftComponent(new JLabel("Unable to load image."));
        }

        String extracted = indexManager.getExtractedTextForPath(imageFile.getAbsolutePath());
        ImageViewerWindow imageViewerWindow = new ImageViewerWindow(mImg, extracted);
        JTextArea textArea = new JTextArea(extracted != null ? extracted : "(no OCR text available)");
        textArea.setEditable(false);
        JScrollPane textScroll = new JScrollPane(textArea);
        split.setRightComponent(textScroll);

        dlg.add(split, BorderLayout.CENTER);

        // quick close on Escape or click outside
        dlg.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    dlg.dispose();
                }
            }
        });
        /*dlg.addMouseListener(new MouseAdapter(){
            @Override public void mouseClicked(MouseEvent e) {
                // don't close on clicks inside the dialog; user can press ESC
            }
        });*/

        // allow resizing by mouse (we keep undecorated but allow dragging from edges):
        // simple implementation: allow dragging the window by mouse when clicking top area
        JPanel dragBar = new JPanel();
        dragBar.setPreferredSize(new Dimension(100, 8));
        dragBar.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        dragBar.addMouseMotionListener(new MouseMotionAdapter() {
            private Point last;

            @Override
            public void mouseDragged(MouseEvent e) {
                if (last != null) {
                    Point loc = dlg.getLocation();
                    dlg.setLocation(loc.x + e.getX() - last.x, loc.y + e.getY() - last.y);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                last = e.getPoint();
            }
        });
        dlg.add(dragBar, BorderLayout.NORTH);

        //dlg.setVisible(true);
    }

    /**
     * *********************
     * IndexManager - handles lucene indexes and metadata per folder
     ***********************
     */
    private static class IndexManager {

        private final File indexesRoot; // storage/indexes

        IndexManager(File storageRoot) {
            this.indexesRoot = new File(storageRoot, INDEXES_DIRNAME);
            if (!indexesRoot.exists()) {
                indexesRoot.mkdirs();
            }
        }

        boolean isIndexed(File folder) {
            File idxDir = getIndexDirForFolder(folder);
            return idxDir.exists() && idxDir.isDirectory();
        }

        private String folderId(File folder) {
            // SHA-1 of absolute path -> safe folder name
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] b = md.digest(folder.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte x : b) {
                    sb.append(String.format("%02x", x));
                }
                return sb.toString();
            } catch (Exception e) {
                return Integer.toHexString(folder.getAbsolutePath().hashCode());
            }
        }

        private File getIndexDirForFolder(File folder) {
            return new File(indexesRoot, folderId(folder));
        }

        // metadata file path (maps file -> lastModified)
        private File getMetadataFile(File folder) {
            return new File(getIndexDirForFolder(folder), "metadata.txt");
        }

        // read metadata -> map path -> lastModified
        private Map<String, Long> readMetadata(File folder) {
            Map<String, Long> map = new HashMap<>();
            File m = getMetadataFile(folder);
            if (!m.exists()) {
                return map;
            }
            try (BufferedReader br = new BufferedReader(new FileReader(m))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length == 2) {
                        map.put(parts[0], Long.parseLong(parts[1]));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return map;
        }

        private void writeMetadata(File folder, Map<String, Long> map) {
            File m = getMetadataFile(folder);
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(m))) {
                for (Map.Entry<String, Long> e : map.entrySet()) {
                    bw.write(e.getKey() + "\t" + e.getValue());
                    bw.newLine();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        // return list of image files in folder (non-recursive) - you can change to recursive if needed
        List<File> listImageFiles(File folder) {
            File[] arr = folder.listFiles((dir, name) -> {
                String ext = getExt(name);
                return ext != null && IMG_EXT.contains(ext.toLowerCase());
            });
            if (arr == null) {
                return List.of();
            }
            return Arrays.asList(arr);
        }

        private static String getExt(String name) {
            int i = name.lastIndexOf('.');
            if (i < 0) {
                return null;
            }
            return name.substring(i + 1);
        }

        // search
        List<String> search(File folder, String queryText, int maxResults) throws Exception {
            File idx = getIndexDirForFolder(folder);
            if (!idx.exists()) {
                return List.of();
            }

            try (Directory dir = FSDirectory.open(idx.toPath()); IndexReader reader = DirectoryReader.open(dir)) {

                IndexSearcher searcher = new IndexSearcher(reader);
                StandardAnalyzer analyzer = new StandardAnalyzer();

                Query q;
                if ("*".equals(queryText) || queryText.trim().isEmpty()) {
                    q = new MatchAllDocsQuery();
                } else {
                    QueryParser parser = new QueryParser("text", analyzer);
                    q = parser.parse(QueryParser.escape(queryText));
                }

                TopDocs top = searcher.search(q, maxResults);
                List<String> results = new ArrayList<>();
                for (ScoreDoc sd : top.scoreDocs) {
                    Document doc = searcher.doc(sd.doc);
                    results.add(doc.get("path"));
                }
                return results;
            }
        }

        String getExtractedTextForPath(String path) {
            // find the index that contains this doc by searching all indexes (could be optimized if we tracked map folder->doc)
            File[] idxs = indexesRoot.listFiles(File::isDirectory);
            if (idxs == null) {
                return null;
            }
            for (File idx : idxs) {
                try (Directory dir = FSDirectory.open(idx.toPath()); IndexReader reader = DirectoryReader.open(dir)) {

                    IndexSearcher searcher = new IndexSearcher(reader);
                    Term term = new Term("path", path);
                    Query q = new TermQuery(term);
                    TopDocs top = searcher.search(q, 1);
                    if (top.totalHits.value > 0) {
                        Document doc = searcher.doc(top.scoreDocs[0].doc);
                        String text = doc.get("text");
                        return text;
                    }
                } catch (Exception e) {
                    // ignore and continue
                }
            }
            return null;
        }

        // The main function to update/create index for a folder. This will:
        // - read metadata file
        // - detect removed files -> delete from index
        // - detect new/modified files -> OCR and add/update lucene
        // - write metadata
        void updateIndex(File folder, ProgressCallback callback, boolean forceReindex) throws Exception {
            File idxDir = getIndexDirForFolder(folder);
            if (!idxDir.exists()) {
                idxDir.mkdirs();
            }

            Map<String, Long> oldMeta = readMetadata(folder);
            List<File> currentFiles = listImageFiles(folder);

            Map<String, Long> newMeta = new HashMap<>();
            for (File f : currentFiles) {
                newMeta.put(f.getAbsolutePath(), f.lastModified());
            }

            // detect removed
            Set<String> removed = oldMeta.keySet().stream()
                    .filter(p -> !newMeta.containsKey(p))
                    .collect(Collectors.toSet());

            // detect added or modified
            List<File> toIndex = new ArrayList<>();
            for (File f : currentFiles) {
                Long prev = oldMeta.get(f.getAbsolutePath());
                if (forceReindex || prev == null || prev != f.lastModified()) {
                    toIndex.add(f);
                }
            }

            // prepare lucene writer
            try (FSDirectory dir = FSDirectory.open(idxDir.toPath())) {
                StandardAnalyzer analyzer = new StandardAnalyzer();
                IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
                try (IndexWriter writer = new IndexWriter(dir, cfg)) {

                    // delete removed
                    for (String p : removed) {
                        callback.message("Removing deleted: " + Paths.get(p).getFileName());
                        writer.deleteDocuments(new Term("path", p));
                    }

                    // OCR engine
                    ITesseract tess = new Tesseract();
                    tess.setDatapath(TESSDATA_PATH);
                    tess.setLanguage(TESS_LANG);

                    int total = toIndex.size();
                    int done = 0;
                    for (File f : toIndex) {
                        callback.message("OCR: " + f.getName() + " (" + (done + 1) + "/" + total + ")");
                        try {
                            // do OCR
                            String text = tess.doOCR(f);

                            // create doc
                            Document doc = new Document();
                            doc.add(new StringField("path", f.getAbsolutePath(), Field.Store.YES));
                            doc.add(new org.apache.lucene.document.TextField("text", text != null ? text : "", Field.Store.YES));
                            doc.add(new StoredField("lastMod", f.lastModified()));

                            // update or add
                            writer.updateDocument(new Term("path", f.getAbsolutePath()), doc);
                        } catch (Exception e) {
                            // OCR failed but continue
                            e.printStackTrace();
                        }
                        done++;
                        int prog = total == 0 ? 100 : (int) ((done / (double) total) * 100);
                        callback.progress(prog);
                    }

                    writer.commit();
                }
            }

            // write new metadata (only for files that currently exist)
            writeMetadata(folder, newMeta);
            callback.message("Index updated: " + folder.getName());
            callback.progress(100);
        }
    }

    // simple callback for progress updates
    private interface ProgressCallback {

        void progress(int percent);

        void message(String msg);
    }

    /**
     * ***********************
     * IndexWorker - SwingWorker wrapper that calls IndexManager.updateIndex
     ************************
     */
    private class IndexWorker extends SwingWorker<Void, Void> {

        private final File folder;
        private final IndexManager manager;
        private final boolean force;

        IndexWorker(File folder, IndexManager manager, boolean force) {
            this.folder = folder;
            this.manager = manager;
            this.force = force;
        }

        @Override
        protected Void doInBackground() throws Exception {
            manager.updateIndex(folder, new ProgressCallback() {
                @Override
                public void progress(int percent) {
                    setProgress(percent);
                    firePropertyChange("progress", null, percent);
                }

                @Override
                public void message(String msg) {
                    firePropertyChange("message", null, msg);
                    statusLabel.setText(msg);
                }
            }, force);
            return null;
        }
    }

    /**
     * ***************
     * WrapLayout - small helper layout to wrap thumbnails Source (lightly
     * adapted) - allows components to wrap like a flow
     ****************
     */
    public static class WrapLayout extends FlowLayout {

        public WrapLayout() {
            super();
        }

        public WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width -= (getHgap() + 1);
            return minimum;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getWidth();
                if (targetWidth == 0) {
                    targetWidth = Integer.MAX_VALUE;
                }

                int hgap = getHgap();
                int vgap = getVgap();
                Insets insets = target.getInsets();
                int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
                int maxWidth = targetWidth - horizontalInsetsAndGap;
                Dimension dim = new Dimension(0, 0);
                int rowWidth = 0;
                int rowHeight = 0;

                int nmembers = target.getComponentCount();

                for (int i = 0; i < nmembers; i++) {
                    Component m = target.getComponent(i);
                    if (!m.isVisible()) {
                        continue;
                    }
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowWidth + d.width > maxWidth) {
                        addRow(dim, rowWidth, rowHeight);
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    rowWidth += d.width + hgap;
                    rowHeight = Math.max(rowHeight, d.height);
                }
                addRow(dim, rowWidth, rowHeight);
                dim.width += horizontalInsetsAndGap;
                dim.height += insets.top + insets.bottom + vgap * 2;
                return dim;
            }
        }

        private void addRow(Dimension dim, int rowWidth, int rowHeight) {
            dim.width = Math.max(dim.width, rowWidth);
            if (dim.height > 0) {
                dim.height += getVgap();
            }
            dim.height += rowHeight;
        }
    }
}
