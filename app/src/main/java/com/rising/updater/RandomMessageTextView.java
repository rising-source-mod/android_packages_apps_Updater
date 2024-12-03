package com.rising.updater;

import android.content.Context;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.annotation.Nullable;
import java.util.Random;

public class RandomMessageTextView extends AppCompatTextView {

    private static final String TAG = "RandomMessageTextView";
    private String[] messages;
    private int lastIndex = -1;

    public RandomMessageTextView(Context context) {
        super(context);
        init(context);
    }

    public RandomMessageTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public RandomMessageTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        messages = context.getResources().getStringArray(R.array.no_updates_messages);
        updateRandomMessage();
    }

    public void updateRandomMessage() {
        String randomMessage;
        if (messages != null && messages.length > 0) {
            int randomIndex;
            do {
                randomIndex = new Random().nextInt(messages.length);
            } while (randomIndex == lastIndex && messages.length > 1);
            lastIndex = randomIndex;
            randomMessage = messages[randomIndex];
        } else {
            randomMessage = getContext().getString(R.string.list_no_updates);
        }

        setText(randomMessage);
    }
}
