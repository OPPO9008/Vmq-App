package com.shinian.pay;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import java.util.function.BooleanSupplier;
import android.service.controls.actions.BooleanAction; // 这个可以不用管

public class HelpActivity extends AppCompatActivity {
    
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_help);
		
		//隐藏标题栏
/*		
		ActionBar actionBar = getSupportActionBar();

		if (actionBar != null) {
			actionBar.hide();
		}
		*/
		
    }
    
}
