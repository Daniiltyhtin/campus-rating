package ru.campusrating.settings.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.settings.api.AdminSettingsController.AdminSettings;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class AdminSettingsService {
    private final SettingsApi settings;
    private final JdbcClient jdbc;
    public AdminSettingsService(SettingsApi settings,JdbcClient jdbc) { this.settings=settings;this.jdbc=jdbc; }
    public AdminSettings read() { return new AdminSettings(settings.penaltyStep(),Math.toIntExact(settings.penaltyInterval().toSeconds()),minimum(),settings.semesterAccrual(),settings.monthlyBudgetLimit()); }
    private int minimum() { var d=settings.minPenaltyInterval(); return Math.toIntExact(d.getSeconds()+(d.getNano()==0?0:1)); }
    @Transactional public AdminSettings save(Map<String,Object> body) {
        var errors=new ArrayList<FieldError>();
        int step=integer(body,"penaltyStep",1,1000,errors);
        int interval=integer(body,"penaltyIntervalSeconds",minimum(),86400,errors);
        int accrual=integer(body,"semesterAccrual",0,1000,errors);
        int limit=integer(body,"monthlyBudgetLimit",0,100000,errors);
        if(!errors.isEmpty()) throw new ValidationFailedException("Проверьте поля формы",errors);
        put("penaltyStep",String.valueOf(step),"INTEGER"); put("penaltyInterval",Duration.ofSeconds(interval).toString(),"DURATION");
        put("semesterAccrual",String.valueOf(accrual),"INTEGER");put("monthlyBudgetLimit",String.valueOf(limit),"INTEGER");
        return read();
    }
    private int integer(Map<String,Object> body,String field,int min,int max,List<FieldError> errors) {
        Object value=body.get(field);
        if(value instanceof Integer number&&number>=min&&number<=max) return number;
        errors.add(new FieldError(field,"INVALID","Допустимо от "+min+" до "+max));return min;
    }
    private void put(String key,String value,String type) {
        jdbc.sql("insert into settings(key,value,type) values(:key,:value,:type) on conflict(key) do update set value=excluded.value,type=excluded.type")
                .param("key",key).param("value",value).param("type",type).update();
    }
}
