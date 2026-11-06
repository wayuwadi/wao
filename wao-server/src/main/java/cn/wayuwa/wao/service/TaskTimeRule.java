package cn.wayuwa.wao.service;

import java.util.ArrayList;
import java.util.List;

/** User-controlled fields only; progress and receipts are owned by the service. */
public class TaskTimeRule {
    public long revision;
    public String repeat = "none";
    public int interval = 1;
    public String mode = "scheduled";
    public String anchor = "start";
    public String until = "";
    public String reminderTime = "09:00";
    public List<Integer> remindBefore = new ArrayList<>();
    /** Preserve Jan 31 -> Feb 28 -> Mar 31 across a series. Not accepted from requests. */
    public int anchorDay;
    public String anchorDate = "";
}
