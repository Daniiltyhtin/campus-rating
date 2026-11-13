package ru.campusrating.identity.internal;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Component
public class ImportFileReader {
    static {
        org.apache.poi.openxml4j.util.ZipSecureFile.setMaxEntrySize(20L*1024*1024);
        org.apache.poi.openxml4j.util.ZipSecureFile.setMaxTextSize(20L*1024*1024);
    }
    public enum Kind {
        GROUPS(List.of("faculty_code","group_code","group_name","admission_year")),
        STUDENTS(List.of("email","last_name","first_name","middle_name","faculty_code","group_code")),
        TEACHER_ALLOWLIST(List.of("email","department_code"));
        final List<String> header;Kind(List<String> header) {this.header=header;}
    }
    public record Row(int number,List<String> values) {}
    public List<Row> read(Kind kind,MultipartFile file) {
        if(file.getSize()>5*1024*1024) throw tooLarge();
        try {
            var name=file.getOriginalFilename();
            var raw=name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".xlsx")?xlsx(file.getBytes()):csv(kind,file.getBytes());
            if(raw.isEmpty()||!raw.getFirst().equals(kind.header)) throw unreadable();
            var result=new ArrayList<Row>();int number=1;
            for(var values:raw.subList(1,raw.size())) {
                if(values.stream().allMatch(String::isBlank)) continue;
                result.add(new Row(++number,values.stream().map(String::strip).toList()));
                if(result.size()>5000) throw tooLarge();
            }
            return result;
        } catch(ApiException e) {throw e;} catch(Exception e) {throw unreadable();}
    }
    private List<List<String>> csv(Kind kind,byte[] bytes) throws Exception {
        String text;
        try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e) {text=new String(bytes,Charset.forName("windows-1251"));}
        if(text.startsWith("\uFEFF")) text=text.substring(1);
        for(char delimiter:new char[]{',',';'}) {
            try(var parser=CSVFormat.RFC4180.builder().setDelimiter(delimiter).get().parse(new StringReader(text))) {
                var iterator=parser.iterator();if(!iterator.hasNext()) continue;
                var header=iterator.next().toList();if(!header.equals(kind.header)) continue;
                var result=new ArrayList<List<String>>();result.add(header);int count=0;
                while(iterator.hasNext()) {var values=iterator.next().toList();if(values.stream().allMatch(String::isBlank)) continue;result.add(values);if(++count>5000) throw tooLarge();}
                return result;
            }
        }
        throw unreadable();
    }
    private List<List<String>> xlsx(byte[] bytes) throws Exception {
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if(workbook.getNumberOfSheets()==0) throw unreadable();
            var sheet=workbook.getSheetAt(0);var format=new DataFormatter(Locale.ROOT);var result=new ArrayList<List<String>>();int count=0;
            if(sheet.getFirstRowNum()!=0||sheet.getRow(0)==null) throw unreadable();
            for(var row:sheet) {
                var values=new ArrayList<String>();for(int i=0;i<row.getLastCellNum();i++) values.add(format.formatCellValue(row.getCell(i)));
                if(values.stream().allMatch(String::isBlank)) continue;
                result.add(values);if(++count>5001) throw tooLarge();
            }
            return result;
        }
    }
    static ApiException tooLarge() {return invalid("Файл слишком большой: до 5 МБ и 5000 строк");}
    static ApiException unreadable() {return invalid("Не удалось прочитать файл: проверьте формат и заголовок");}
    private static ApiException invalid(String message) {return new ru.campusrating.shared.error.ValidationFailedException(message,List.of(new ru.campusrating.shared.error.FieldError("file","INVALID",message)));}
}
