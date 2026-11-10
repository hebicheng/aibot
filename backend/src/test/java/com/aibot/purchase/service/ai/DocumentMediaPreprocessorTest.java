package com.aibot.purchase.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DocumentMediaPreprocessorTest {

  @Test
  void extractsWorksheetRowsForModelAnalysis() throws Exception {
    byte[] workbookBytes;
    try (XSSFWorkbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("付款记录");
      var header = sheet.createRow(0);
      header.createCell(0).setCellValue("付款日期");
      header.createCell(1).setCellValue("收款单位");
      header.createCell(2).setCellValue("付款金额");
      var row = sheet.createRow(1);
      row.createCell(0).setCellValue("2026-07-22");
      row.createCell(1).setCellValue("上海找钢网信息科技股份有限公司");
      row.createCell(2).setCellValue(100000);
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }

    var prepared = new DocumentMediaPreprocessor().prepare(
        workbookBytes,
        "付款记录.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    assertThat(prepared.fileType()).isEqualTo("excel");
    assertThat(prepared.mediaInputs()).isEmpty();
    assertThat(prepared.extractedText())
        .contains("工作表：付款记录")
        .contains("付款日期\t收款单位\t付款金额")
        .contains("2026-07-22\t上海找钢网信息科技股份有限公司\t100000");
  }
}
