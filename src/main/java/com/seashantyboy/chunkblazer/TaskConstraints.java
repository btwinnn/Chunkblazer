package com.seashantyboy.chunkblazer;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class TaskConstraints
{
    @SerializedName("time_in_ticks")
    private Integer timeInTicks;

    public boolean hasTimeLimit()
    {
        return timeInTicks != null && timeInTicks > 0;
    }

    public double getTimeInSeconds()
    {
        if (timeInTicks == null)
        {
            return 0;
        }
        // 1 game tick = 0.6 seconds
        return timeInTicks * 0.6;
    }
}
