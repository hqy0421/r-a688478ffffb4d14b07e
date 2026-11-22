package technology.tabula;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Test;

import technology.tabula.unpivot.UnpivotException;
import technology.tabula.unpivot.UnpivotSpec;
import technology.tabula.writers.UnpivotedCSVWriter;

public class TestUnpivotedCSVWriter {

    private static final UnpivotSpec SPEC = new UnpivotSpec(
            Arrays.asList("门店", "商品"),
            Arrays.asList("销量", "销售额"));

    private static Table table(int pageNumber, String[][] grid) {
        Table table = Table.empty();
        table.setPageNumber(pageNumber);
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[r].length; c++) {
                table.add(chunk(grid[r][c]), r, c);
            }
        }
        return table;
    }

    private static TextChunk chunk(String text) {
        TextElement element = new TextElement(
                0, 0, 10, 10,
                new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                10, text, 5);
        return new TextChunk(element);
    }

    private String write(UnpivotSpec spec, Table... tables) throws IOException {
        StringBuilder sb = new StringBuilder();
        new UnpivotedCSVWriter(spec).write(sb, Arrays.asList(tables));
        return sb.toString();
    }

    @Test
    public void testMeltsWideTableOneRowPerMonthAscending() throws IOException {
        // Header columns deliberately out of order vs. the configured order.
        Table t = table(1, new String[][]{
                {"商品", "销量 2025-02", "销量 2025-01", "门店", "销售额 2025-01"},
                {"苹果", "30",          "10",          "S01",  "100.5"},
        });

        String csv = write(SPEC, t);
        assertEquals(
                "门店,商品,月份,销量,销售额\r\n"
                + "S01,苹果,2025-01,10,100.5\r\n"
                + "S01,苹果,2025-02,30,\r\n",
                csv);
    }

    @Test
    public void testAcceptsChineseMonthFormAndNormalizes() throws IOException {
        Table t = table(3, new String[][]{
                {"门店", "商品", "销量 2025年1月", "销售额 2025年1月"},
                {"S01", "梨",   "7",             "42"},
        });

        String csv = write(SPEC, t);
        assertEquals(
                "门店,商品,月份,销量,销售额\r\n"
                + "S01,梨,2025-01,7,42\r\n",
                csv);
    }

    @Test
    public void testZeroIsKeptAndBlankMonthIsSkipped() throws IOException {
        Table t = table(1, new String[][]{
                {"门店", "商品", "销量 2025-01", "销售额 2025-01", "销量 2025-02", "销售额 2025-02"},
                {"S01", "梨",   "0",            "",              "",             ""},
        });

        String csv = write(SPEC, t);
        // 2025-01 survives because the zero is a real value; 2025-02 is all blank and skipped.
        assertEquals(
                "门店,商品,月份,销量,销售额\r\n"
                + "S01,梨,2025-01,0,\r\n",
                csv);
    }

    @Test
    public void testMultipleSourceRowsKeepOrderWithMonthsSortedWithinEach() throws IOException {
        Table t = table(1, new String[][]{
                {"门店", "商品", "销量 2025-03", "销量 2025-01"},
                {"S01", "苹果", "3",            "1"},
                {"S02", "梨",   "4",            "2"},
        });

        String csv = write(SPEC, t);
        assertEquals(
                "门店,商品,月份,销量,销售额\r\n"
                + "S01,苹果,2025-01,1,\r\n"
                + "S01,苹果,2025-03,3,\r\n"
                + "S02,梨,2025-01,2,\r\n"
                + "S02,梨,2025-03,4,\r\n",
                csv);
    }

    @Test
    public void testReorderedHeaderOnAnotherPageWritesHeaderOnce() throws IOException {
        Table page1 = table(1, new String[][]{
                {"门店", "商品", "销量 2025-01"},
                {"S01", "苹果", "1"},
        });
        Table page2 = table(2, new String[][]{
                {"商品", "销量 2025-02", "门店"},
                {"梨",   "2",           "S02"},
        });

        String csv = write(SPEC, page1, page2);
        assertEquals(
                "门店,商品,月份,销量,销售额\r\n"
                + "S01,苹果,2025-01,1,\r\n"
                + "S02,梨,2025-02,2,\r\n",
                csv);
        assertEquals(1, countOccurrences(csv, "门店,商品,月份,销量,销售额"));
    }

    @Test
    public void testConflictSameMetricAndMonthReportsPageAndColumn() {
        Table page1 = table(1, new String[][]{
                {"门店", "商品", "销量 2025-01"},
                {"S01", "苹果", "1"},
        });
        Table page2 = table(2, new String[][]{
                {"门店", "商品", "销量 2025年1月"},
                {"S02", "梨",   "2"},
        });

        UnpivotException e = assertThrows(UnpivotException.class, () -> write(SPEC, page1, page2));
        String message = e.getMessage();
        assertTrue(message, message.contains("销量"));
        assertTrue(message, message.contains("2025-01"));
        assertTrue(message, message.contains("page 1 column 3"));
        assertTrue(message, message.contains("page 2 column 3"));
    }

    @Test
    public void testIllegalIsoMonthReportsPageAndColumn() {
        Table t = table(4, new String[][]{
                {"门店", "商品", "销量 2025-13"},
                {"S01", "苹果", "1"},
        });
        UnpivotException e = assertThrows(UnpivotException.class, () -> write(SPEC, t));
        assertTrue(e.getMessage(), e.getMessage().contains("Illegal month '2025-13'"));
        assertTrue(e.getMessage(), e.getMessage().contains("page 4 column 3"));
    }

    @Test
    public void testIllegalChineseMonthReportsPageAndColumn() {
        Table t = table(1, new String[][]{
                {"门店", "商品", "销量 2025年00月"},
                {"S01", "苹果", "1"},
        });
        UnpivotException e = assertThrows(UnpivotException.class, () -> write(SPEC, t));
        assertTrue(e.getMessage(), e.getMessage().contains("Illegal month '2025年00月'"));
        assertTrue(e.getMessage(), e.getMessage().contains("column 3"));
    }

    @Test
    public void testMalformedDateTokenIsRejected() {
        Table t = table(1, new String[][]{
                {"门店", "商品", "销量 2025/1"},
                {"S01", "苹果", "1"},
        });
        UnpivotException e = assertThrows(UnpivotException.class, () -> write(SPEC, t));
        assertTrue(e.getMessage(), e.getMessage().contains("Illegal month '2025/1'"));
    }

    @Test
    public void testMissingDimensionColumnReportsPage() {
        Table t = table(2, new String[][]{
                {"门店", "销量 2025-01"},
                {"S01", "1"},
        });
        UnpivotException e = assertThrows(UnpivotException.class, () -> write(SPEC, t));
        assertTrue(e.getMessage(), e.getMessage().contains("Missing dimension column '商品'"));
        assertTrue(e.getMessage(), e.getMessage().contains("page 2"));
    }

    @Test
    public void testUnknownMetricIsRejected() {
        Table t = table(1, new String[][]{
                {"门店", "商品", "库存 2025-01"},
                {"S01", "苹果", "1"},
        });
        UnpivotException e = assertThrows(UnpivotException.class, () -> write(SPEC, t));
        assertTrue(e.getMessage(), e.getMessage().contains("Unknown metric '库存'"));
        assertTrue(e.getMessage(), e.getMessage().contains("column 3"));
    }

    @Test
    public void testNonDataColumnsAreIgnoredAndMissingCellsStayBlank() throws IOException {
        Table t = table(1, new String[][]{
                {"门店", "商品", "备注", "销量 2025-01"},
                {"S01", "苹果", "加急", "1"},
                {"S02", "梨",   "",     ""},
        });

        String csv = write(SPEC, t);
        assertEquals(
                "门店,商品,月份,销量,销售额\r\n"
                + "S01,苹果,2025-01,1,\r\n",
                csv);
    }

    @Test
    public void testNoTablesStillWritesTheFixedHeaderOnce() throws IOException {
        String csv = write(SPEC);
        assertEquals("门店,商品,月份,销量,销售额\r\n", csv);
    }

    @Test
    public void testHeaderOnlyTableWritesJustHeader() throws IOException {
        Table t = table(1, new String[][]{
                {"门店", "商品", "销量 2025-01"},
        });
        String csv = write(SPEC, t);
        assertEquals("门店,商品,月份,销量,销售额\r\n", csv);
    }

    @Test
    public void testMetricNameMayContainSpaces() throws IOException {
        UnpivotSpec spec = new UnpivotSpec(
                Arrays.asList("门店"),
                Arrays.asList("净 销量"));
        Table t = table(1, new String[][]{
                {"门店", "净 销量 2025-01"},
                {"S01", "9"},
        });
        String csv = write(spec, t);
        assertEquals(
                "门店,月份,净 销量\r\n"
                + "S01,2025-01,9\r\n",
                csv);
    }

    @Test
    public void testDuplicateDimensionInHeaderIsRejected() {
        Table t = table(1, new String[][]{
                {"门店", "门店", "商品", "销量 2025-01"},
                {"S01", "S01", "苹果", "1"},
        });
        UnpivotException e = assertThrows(UnpivotException.class, () -> write(SPEC, t));
        assertTrue(e.getMessage(), e.getMessage().contains("Duplicate dimension column '门店'"));
        assertTrue(e.getMessage(), e.getMessage().contains("column 2"));
    }

    @Test
    public void testConflictWithinSameTableReportsPageAndColumn() {
        Table t = table(1, new String[][]{
                {"门店", "商品", "销量 2025-01", "销量 2025年1月"},
                {"S01", "苹果", "1", "2"},
        });
        UnpivotException e = assertThrows(UnpivotException.class, () -> write(SPEC, t));
        assertTrue(e.getMessage(), e.getMessage().contains("Conflicting columns"));
        assertTrue(e.getMessage(), e.getMessage().contains("销量"));
        assertTrue(e.getMessage(), e.getMessage().contains("page 1 column 4"));
    }

    @Test
    public void testIdeographicSpaceSeparatorIsAccepted() throws IOException {
        Table t = table(1, new String[][]{
                {"门店", "商品", "销量　2025年1月"},
                {"S01", "苹果", "5"},
        });
        String csv = write(SPEC, t);
        assertEquals(
                "门店,商品,月份,销量,销售额\r\n"
                + "S01,苹果,2025-01,5,\r\n",
                csv);
    }

    @Test
    public void testNonBreakingSpaceSeparatorIsAccepted() throws IOException {
        Table t = table(1, new String[][]{
                {"门店", "商品", "销量 2025-01"},
                {"S01", "苹果", "5"},
        });
        String csv = write(SPEC, t);
        assertEquals(
                "门店,商品,月份,销量,销售额\r\n"
                + "S01,苹果,2025-01,5,\r\n",
                csv);
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

    @Test
    public void testSpecExposesOrder() {
        List<String> dims = new ArrayList<>(SPEC.getDimensions());
        List<String> metrics = new ArrayList<>(SPEC.getMetrics());
        assertEquals(Arrays.asList("门店", "商品"), dims);
        assertEquals(Arrays.asList("销量", "销售额"), metrics);
        try {
            SPEC.getDimensions().add("extra");
            fail("dimension list should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
