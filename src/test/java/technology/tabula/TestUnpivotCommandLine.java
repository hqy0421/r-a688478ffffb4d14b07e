package technology.tabula;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestUnpivotCommandLine {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String run(String[] args) throws ParseException {
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(CommandLineApp.buildOptions(), args);

        StringBuilder sb = new StringBuilder();
        new CommandLineApp(sb, cmd).extractTables(cmd);
        return sb.toString();
    }

    @Test
    public void testDimensionsWithoutUnpivotFlagIsRejected() {
        ParseException e = assertThrows(ParseException.class, () -> run(new String[]{
                "whatever.pdf", "--dimensions", "store,product"
        }));
        assertTrue(e.getMessage(), e.getMessage().contains("--unpivot"));
    }

    @Test
    public void testUnpivotWithoutMetricsIsRejected() {
        ParseException e = assertThrows(ParseException.class, () -> run(new String[]{
                "whatever.pdf", "--unpivot", "--dimensions", "store,product"
        }));
        assertTrue(e.getMessage(), e.getMessage().contains("--metrics"));
    }

    @Test
    public void testUnpivotWithJsonIsRejected() {
        ParseException e = assertThrows(ParseException.class, () -> run(new String[]{
                "whatever.pdf", "--unpivot",
                "--dimensions", "store", "--metrics", "sales",
                "-f", "JSON"
        }));
        assertTrue(e.getMessage(), e.getMessage().contains("CSV"));
    }

    @Test
    public void testEndToEndAcrossTwoPagesWithReorderedHeaders() throws IOException, ParseException {
        File pdf = folder.newFile("wide.pdf");
        createRuledPdf(pdf);

        String csv = run(new String[]{
                pdf.getAbsolutePath(),
                "-p", "all", "-l", "-f", "CSV",
                "--unpivot",
                "--dimensions", "store,product",
                "--metrics", "sales"
        });

        // Fixed header appears exactly once even though each page has its own header row.
        assertEquals(1, countOccurrences(csv, "store,product,月份,sales"));
        assertEquals(
                "store,product,月份,sales\r\n"
                + "S01,Apple,2025-01,10\r\n"
                + "S01,Apple,2025-02,30\r\n"
                + "S02,Pear,2025-03,20\r\n",
                csv);
    }

    /**
     * Draws two pages of ruled tables with text inside the cells so lattice-mode
     * extraction recognizes them. The second page reorders its columns.
     */
    private static void createRuledPdf(File file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            drawPage(doc, font, new String[][]{
                    {"store", "product", "sales 2025-01", "sales 2025-02"},
                    {"S01", "Apple", "10", "30"},
            });
            drawPage(doc, font, new String[][]{
                    {"product", "sales 2025-03", "store"},
                    {"Pear", "20", "S02"},
            });

            doc.save(file);
        }
    }

    private static void drawPage(PDDocument doc, PDType1Font font, String[][] grid)
            throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);

        float[] colEdges = {50f, 150f, 250f, 350f, 450f};
        int cols = grid[0].length;
        float headerTop = 100f;
        float rowHeight = 30f;
        float bottom = headerTop + rowHeight * grid.length;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setLineWidth(0.5f);

            for (float x : colEdges) {
                if (x > colEdges[cols]) {
                    break;
                }
                cs.moveTo(x, headerTop);
                cs.lineTo(x, bottom);
            }
            for (int r = 0; r <= grid.length; r++) {
                float y = headerTop + r * rowHeight;
                cs.moveTo(colEdges[0], y);
                cs.lineTo(colEdges[cols], y);
            }
            cs.stroke();

            cs.beginText();
            cs.setFont(font, 8f);
            for (int r = 0; r < grid.length; r++) {
                for (int c = 0; c < cols; c++) {
                    float x = colEdges[c] + 4f;
                    // Rows are drawn bottom-up in PDF space so the header is physically on top.
                    float y = bottom - (r + 1) * rowHeight + rowHeight / 2 - 3f;
                    cs.newLineAtOffset(x, y);
                    cs.showText(grid[r][c]);
                    cs.newLineAtOffset(-x, -y);
                }
            }
            cs.endText();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
