package ru.popov.telegrambot.service;

import lombok.SneakyThrows;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import ru.popov.telegrambot.DAO.EventCashDAO;
import ru.popov.telegrambot.DAO.EventDAO;
import ru.popov.telegrambot.config.ApplicationContextProvider;
import ru.popov.telegrambot.entity.Event;
import ru.popov.telegrambot.entity.EventCashEntity;
import ru.popov.telegrambot.model.EventFreq;
import ru.popov.telegrambot.model.TelegramBot;

import javax.annotation.PostConstruct;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.stream.Collectors;

@Service
@EnableScheduling
public class EventService {
    private final EventDAO eventDAO;
    private final EventCashDAO eventCashDAO;

    public EventService(EventDAO eventDAO, EventCashDAO eventCashDAO) {
        this.eventDAO = eventDAO;
        this.eventCashDAO = eventCashDAO;
    }

    //start service in 0:00 every day
    @Scheduled(cron = "0 0 0 * * *")
    // @Scheduled(fixedRateString = "${eventservice.period}")
    private void eventService() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int month = calendar.get(Calendar.MONTH);
        int year = calendar.get(Calendar.YEAR);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);//need, if user set time 0:00

        //get event list is now date
        List<Event> list = eventDAO.findAllEvent().stream().filter(event -> {
            if (event.getUser().isOn()) {
                EventFreq eventFreq = event.getFreq();

                //set user event time
                Calendar calendarUserTime =getDateUserTimeZone(event);

                int day1 = calendarUserTime.get(Calendar.DAY_OF_MONTH);
                int month1 = calendarUserTime.get(Calendar.MONTH);
                int year1 = calendarUserTime.get(Calendar.YEAR);
                switch (eventFreq.name()) {
                    case "TIME": //if one time - remove event
                        if (day == day1 && month == month1 && year == year1) {
                            eventDAO.remove(event);
                            return true;
                        }
                    case "EVERYDAY":
                        return true;
                    case "MONTH":
                        if (day == day1) return true;
                    case "YEAR":
                        if (day == day1 && month == month1) return true;
                    default: return false;
                }
            } else return false;
        }).collect(Collectors.toList());

        for (Event event : list) {
            //set user event time
            Calendar calendarUserTime = getDateUserTimeZone(event);

            String description = event.getDescription();
            String userId = String.valueOf(event.getUser().getId());

            int hour1 = calendarUserTime.get(Calendar.HOUR_OF_DAY); //need, if user set time 0:00

            //save the event to the database in case the server reboots.
            EventCashEntity eventCashEntity = EventCashEntity.eventTo(calendarUserTime.getTime(), event.getDescription(), event.getUser().getId());
            eventCashDAO.save(eventCashEntity);

            //create a thread for the upcoming event with the launch at a specific time
            SendEvent sendEvent = new SendEvent();
            sendEvent.setSendMessage(new SendMessage(userId, description));
            sendEvent.setEventCashId(eventCashEntity.getId());

            if (hour == hour1 + event.getUser().getTimeZone()) { //need, if user set time 0:00
                calendarUserTime.add(Calendar.SECOND, 10); //if the user entered 0:00, then the thread will not have time to work
            }
            new Timer().schedule(new SimpleTask(sendEvent), calendarUserTime.getTime());
        }
    }

    private Calendar getDateUserTimeZone(Event event) {
        Calendar calendarUserTime = Calendar.getInstance();
        calendarUserTime.setTime(event.getDate());
        int timeZone = event.getUser().getTimeZone();

        //set correct event time with user timezone
        calendarUserTime.add(Calendar.HOUR_OF_DAY, -timeZone);
        return calendarUserTime;
    }

    @PostConstruct
    @SneakyThrows
    //after every restart app  - check unspent events
    private void afterStart() {
        List<EventCashEntity> list = eventCashDAO.findAllEventCash();

        if (!list.isEmpty()) {
            for (EventCashEntity eventCashEntity : list) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(eventCashEntity.getDate());
                SendEvent sendEvent = new SendEvent();
                sendEvent.setSendMessage(new SendMessage(String.valueOf(eventCashEntity.getUserId()), eventCashEntity.getDescription()));
                sendEvent.setEventCashId(eventCashEntity.getId());
                new Timer().schedule(new SimpleTask(sendEvent), calendar.getTime());
            }
        } else {
            //just inform you that there was a reboot
            TelegramBot telegramBot = ApplicationContextProvider.getApplicationContext().getBean(TelegramBot.class);
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(String.valueOf(000000));
            sendMessage.setText("Произошла перезагрузка!");
            telegramBot.execute(sendMessage);
        }
    }
}