/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android2.calculator3;

import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup.LayoutParams;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.app.Activity;
import android.content.Context;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;

import net.sf.jchemistry.util.CommonMathUtils;

import org.achartengine.GraphicalView;
import org.achartengine.model.XYSeries;
import org.achartengine.util.MathHelper;
import org.apache.commons.math3.exception.DimensionMismatchException;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixDimensionMismatchException;
import org.apache.commons.math3.linear.NonSymmetricMatrixException;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.javia.arity.Complex;
import org.javia.arity.Symbols;
import org.javia.arity.SyntaxException;

import com.android2.calculator3.CalculatorDisplay.Scroll;

class Logic {
    private final static String REGEX_NUMBER = "[A-F0-9\\.,]";
    private final static String REGEX_NOT_NUMBER = "[^A-F0-9\\.,]";

    private CalculatorDisplay mDisplay;
    private Symbols mSymbols = new Symbols();
    private History mHistory;
    private String  mResult = "";
    private boolean mIsError = false;
    private int mLineLength = 0;
    private Graph mGraph;
    private Activity mContext;

    private static final String INFINITY_UNICODE = "\u221e";

    public static final String MARKER_EVALUATE_ON_RESUME = "?";

    // the two strings below are the result of Double.toString() for Infinity & NaN
    // they are not output to the user and don't require internationalization
    private static final String INFINITY = "Infinity";
    private static final String NAN      = "NaN";

    static final char MINUS = '\u2212';

    private final String mErrorString;
    private final String mSinString;
    private final String mCosString;
    private final String mTanString;
    private final String mLogString;
    private final String mLnString;
    private final String mModString;
    private final String mTitleString;
    private final String mX;
    private final String mY;
    private final String mPlusString; 
    private final String mMinusString;
    private final String mDivString;
    private final String mMulString;
    private final String mDotString;
    private final String mComaString;
    private final String mPowerString;
    private final String mSqrtString;
    private final String mIntegralString;

    public final static int DELETE_MODE_BACKSPACE = 0;
    public final static int DELETE_MODE_CLEAR = 1;

    private int mDeleteMode = DELETE_MODE_BACKSPACE;
    
    public enum Mode {
        BINARY(0), DECIMAL(1), HEXADECIMAL(2);
         
         int quickSerializable;
         
         Mode(int num) {
             this.quickSerializable = num;
         }
         
         public int getQuickSerializable() {
             return quickSerializable;
         }
    }
    
    private Mode mode = Mode.DECIMAL;

    public interface Listener {
        void onDeleteModeChange();
    }

    private Listener mListener;

    Logic(Activity context, History history, CalculatorDisplay display) {
        mContext = context;
        
        mErrorString = context.getResources().getString(R.string.error);
        mSinString = context.getResources().getString(R.string.sin);
        mCosString = context.getResources().getString(R.string.cos);
        mTanString = context.getResources().getString(R.string.tan);
        mLogString = context.getResources().getString(R.string.lg);
        mLnString = context.getResources().getString(R.string.ln);
        mModString = context.getResources().getString(R.string.mod);
        mX = context.getResources().getString(R.string.X);
        mY = context.getResources().getString(R.string.Y);
        mTitleString = context.getResources().getString(R.string.graphTitle);
        mPlusString = context.getResources().getString(R.string.plus); 
        mMinusString = context.getResources().getString(R.string.minus);
        mDivString = context.getResources().getString(R.string.div);
        mMulString = context.getResources().getString(R.string.mul);
        mDotString = context.getResources().getString(R.string.dot);
        mComaString = context.getResources().getString(R.string.coma);
        mPowerString = context.getResources().getString(R.string.power);
        mSqrtString = context.getResources().getString(R.string.sqrt);
        mIntegralString = context.getResources().getString(R.string.integral);
        
        mHistory = history;
        mDisplay = display;
        mDisplay.setLogic(this);
    }
    
    public void setGraph(Graph graph) {
        mGraph = graph;
    }

    public void setListener(Listener listener) {
        this.mListener = listener;
    }

    public void setDeleteMode(int mode) {
        if (mDeleteMode != mode) {
            mDeleteMode = mode;
            mListener.onDeleteModeChange();
        }
    }

    public int getDeleteMode() {
        return mDeleteMode;
    }

    void setLineLength(int nDigits) {
        mLineLength = nDigits;
    }

    boolean eatHorizontalMove(boolean toLeft) {
        EditText editText = mDisplay.getEditText();
        int cursorPos = editText.getSelectionStart();
        return toLeft ? cursorPos == 0 : cursorPos >= editText.length();
    }

    public String getText() {
        String text;
        try{
            text = mDisplay.getText().toString();
        } catch(IndexOutOfBoundsException e){
            text = "";
        }
        return text;
    }
    
    void setText(String text) {
        clear(false);
        mDisplay.insert(text);
        if(text.equals(mErrorString)) setDeleteMode(DELETE_MODE_CLEAR);
    }

    void insert(String delta) {
        mDisplay.insert(delta);
        setDeleteMode(DELETE_MODE_BACKSPACE);
        updateGraph(mGraph);
    }

    public void onTextChanged() {
        setDeleteMode(DELETE_MODE_BACKSPACE);
    }

    public void resumeWithHistory() {
        clearWithHistory(false);
    }

    private void clearWithHistory(boolean scroll) {
        String text = mHistory.getText();
        if (MARKER_EVALUATE_ON_RESUME.equals(text)) {
            if (!mHistory.moveToPrevious()) {
                text = "";
            }
            text = mHistory.getBase();
            evaluateAndShowResult(text, CalculatorDisplay.Scroll.NONE);
        } else {
            mResult = "";
            mDisplay.setText(
                    text, scroll ? CalculatorDisplay.Scroll.UP : CalculatorDisplay.Scroll.NONE);
            mIsError = false;
        }
    }

    private void clear(boolean scroll) {
        mHistory.enter("", "");
        mDisplay.setText("", scroll ? CalculatorDisplay.Scroll.UP : CalculatorDisplay.Scroll.NONE);
        cleared();
    }

    void cleared() {
        mResult = "";
        mIsError = false;
        updateHistory();

        setDeleteMode(DELETE_MODE_BACKSPACE);
    }

    boolean acceptInsert(String delta) {
        String text = getText();
        return !mIsError &&
            (!mResult.equals(text) ||
             isOperator(delta) ||
             mDisplay.getSelectionStart() != text.length());
    }

    void onDelete() {
        if (getText().equals(mResult) || mIsError) {
            clear(false);
        } else {
            mDisplay.dispatchKeyEvent(new KeyEvent(0, KeyEvent.KEYCODE_DEL));
            mResult = "";
        }
        updateGraph(mGraph);
    }

    void onClear() {
        clear(mDeleteMode == DELETE_MODE_CLEAR);
        updateGraph(mGraph);
    }

    void onEnter() {
        if (mDeleteMode == DELETE_MODE_CLEAR) {
            clearWithHistory(false); // clear after an Enter on result
        }
        else {
            evaluateAndShowResult(getText(), CalculatorDisplay.Scroll.UP);
        }
    }

    public void evaluateAndShowResult(String text, Scroll scroll) {
        try {
            String result = evaluate(text);
            if (!text.equals(result)) {
                mHistory.enter(text, result);
                mResult = result;
                mDisplay.setText(mResult, scroll);
                setDeleteMode(DELETE_MODE_CLEAR);
            }
        } catch (SyntaxException e) {
            mIsError = true;
            mResult = mErrorString;
            mDisplay.setText(mResult, scroll);
            setDeleteMode(DELETE_MODE_CLEAR);
        }
    }

    void onUp() {
        if (mHistory.moveToPrevious()) {
            mDisplay.setText(mHistory.getText(), CalculatorDisplay.Scroll.DOWN);
        }
    }

    void onDown() {
        if (mHistory.moveToNext()) {
            mDisplay.setText(mHistory.getText(), CalculatorDisplay.Scroll.UP);
        }
    }

    void updateHistory() {
        String text = getText();
        mHistory.update(text);
    }

    public static final int ROUND_DIGITS = 1;
    String evaluate(String input) throws SyntaxException {
        if (input.trim().isEmpty()) {
            return "";
        }

        // Drop final infix operators (they can only result in error)
        int size = input.length();
        while (size > 0 && isOperator(input.charAt(size - 1))) {
            input = input.substring(0, size - 1);
            --size;
        }

        input = localize(input);
        
        // Convert to decimal
        String decimalInput = updateTextToNewMode(input, mode, Mode.DECIMAL);
        
        Complex value = mSymbols.evalComplex(decimalInput);

        String real = "";
        for (int precision = mLineLength; precision > 6; precision--) {
            real = tryFormattingWithPrecision(value.re, precision);
            if (real.length() <= mLineLength) {
                break;
            }
        }

        String imaginary = "";
        for (int precision = mLineLength; precision > 6; precision--) {
            imaginary = tryFormattingWithPrecision(value.im, precision);
            if (imaginary.length() <= mLineLength) {
                break;
            }
        }

        String result = "";
        if(value.re != 0 && value.im != 0) result = real + "+" + imaginary + "i";
        else if(value.re != 0 && value.im == 0) result = real;
        else if(value.re == 0 && value.im != 0) result = imaginary + "i";
        else if(value.re == 0 && value.im == 0) result = "0";
        return updateTextToNewMode(result, Mode.DECIMAL, mode).replace('-', MINUS).replace(INFINITY, INFINITY_UNICODE);
    }

    private String localize(String input) {
        // Delocalize functions (e.g. Spanish localizes "sin" as "sen")
        input = input.replaceAll(mSinString, "sin");
        input = input.replaceAll(mCosString, "cos");
        input = input.replaceAll(mTanString, "tan");
        input = input.replaceAll(mLogString, "log");
        input = input.replaceAll(mLnString, "ln");
        input = input.replaceAll(mModString, "mod");
        input = input.replaceAll(",", ".");
        return input;
    }

    private String tryFormattingWithPrecision(double value, int precision) {
        // The standard scientific formatter is basically what we need. We will
        // start with what it produces and then massage it a bit.
        String format = "#.";
        for(int i=0;i<precision;i++) format += "#";
        NumberFormat formatter = new DecimalFormat(format);
        String result = formatter.format(value);
        if (result.equals(NAN)) { // treat NaN as Error
            mIsError = true;
            return mErrorString;
        }
        String mantissa = result;
        String exponent = null;
        int e = result.indexOf('e');
        if (e != -1) {
            mantissa = result.substring(0, e);

            // Strip "+" and unnecessary 0's from the exponent
            exponent = result.substring(e + 1);
            if (exponent.startsWith("+")) {
                exponent = exponent.substring(1);
            }
            exponent = String.valueOf(Integer.parseInt(exponent));
        } else {
            mantissa = result;
        }

        int period = mantissa.indexOf('.');
        if (period == -1) {
            period = mantissa.indexOf(',');
        }
        if (period != -1) {
            // Strip trailing 0's
            while (mantissa.length() > 0 && mantissa.endsWith("0")) {
                mantissa = mantissa.substring(0, mantissa.length() - 1);
            }
            if (mantissa.length() == period + 1) {
                mantissa = mantissa.substring(0, mantissa.length() - 1);
            }
        }

        if (exponent != null) {
            result = mantissa + 'e' + exponent;
        } else {
            result = mantissa;
        }
        return result;
    }

    static boolean isOperator(String text) {
        return text.length() == 1 && isOperator(text.charAt(0));
    }

    static boolean isOperator(char c) {
        //plus minus times div
        return "+\u2212\u00d7\u00f7/*".indexOf(c) != -1;
    }
    
    void updateGraph(final Graph g) {
        if(g == null) return;
        final String eq = getText();

        if(eq.isEmpty()) {
            String title = mTitleString + eq;
            XYSeries series = new XYSeries(title);
            
            try{
                g.getDataset().removeSeries(g.getSeries());
                g.setSeries(series);
                g.getDataset().addSeries(series);
            }catch (NullPointerException e) {
                e.printStackTrace();
            }

            GraphicalView graph = (GraphicalView) mContext.findViewById(R.id.graphView);
            
            if(graph!=null) graph.repaint();
            
            return;
        }

        if(!eq.contains("=")) return;
        if(eq.endsWith(mPlusString) || 
           eq.endsWith(mMinusString) || 
           eq.endsWith(mDivString) || 
           eq.endsWith(mMulString) || 
           eq.endsWith(mDotString) ||
           eq.endsWith(mComaString) ||
           eq.endsWith(mPowerString) ||
           eq.endsWith(mSqrtString) ||
           eq.endsWith(mIntegralString) ||
           eq.endsWith(mSinString + "(") || 
           eq.endsWith(mCosString + "(") ||
           eq.endsWith(mTanString + "(") ||
           eq.endsWith(mLogString + "(") || 
           eq.endsWith(mModString + "(") ||
           eq.endsWith(mLnString + "(")) return;

        final String[] equation = eq.split("=", 2);

        if(equation.length == 1) return;

        // Translate into decimal
        equation[0] = updateTextToNewMode(localize(equation[0]), mode, Mode.DECIMAL);
        equation[1] = updateTextToNewMode(localize(equation[1]), mode, Mode.DECIMAL);
        
        new Thread(new Runnable() {
            public void run() {
                final String title = mTitleString + eq;
                final XYSeries series = new XYSeries(title);
                final GraphicalView graph = (GraphicalView) mContext.findViewById(R.id.graphView);

                if(equation[0].equals(mY) && !equation[1].contains(mY)) {
                    for(double x=Graph.MIN_HEIGHT_X;x<=Graph.MAX_HEIGHT_X;x+=(0.00125*(Graph.MAX_HEIGHT_X-Graph.MIN_HEIGHT_X))) {
                        if(!eq.equals(getText())) return;
                        
                        try{
                            mSymbols.define(mX, x);
                            double y = mSymbols.eval(equation[1]);
                            
                            if(y>(Graph.MAX_HEIGHT_Y*2) || y<(Graph.MIN_HEIGHT_Y*2) || y==Double.NaN) {
                                //If we're not exactly on the mark with a break in the graph, we get lines where we shouldn't like with y=1/x
                                //Better to be safe and just treat anything a lot larger than the min/max height to be a break then pray we're perfect and get NaN
                                series.add(x, MathHelper.NULL_VALUE);
                            }
                            else{
                                series.add(x, y);
                            }
                        } catch(SyntaxException e) {
                            e.printStackTrace();
                        }
                    }
                }
                else if(equation[0].equals(mX) && !equation[1].contains(mX)) {
                    for(double y=Graph.MIN_HEIGHT_Y;y<=Graph.MAX_HEIGHT_Y;y+=(0.00125*(Graph.MAX_HEIGHT_Y-Graph.MIN_HEIGHT_Y))) {
                        if(!eq.equals(getText())) return;
                        
                        try{
                            mSymbols.define(mY, y);
                            double x = mSymbols.eval(equation[1]);
                            
                            if(x>(Graph.MAX_HEIGHT_X*2) || x<(Graph.MIN_HEIGHT_X*2) || x==Double.NaN) {
                                series.add(MathHelper.NULL_VALUE, y);
                            }
                            else{
                                series.add(x, y);
                            }
                        } catch(SyntaxException e) {
                            e.printStackTrace();
                        }
                    }
                }
                else if(equation[1].equals(mY) && !equation[0].contains(mY)) {
                    for(double x=Graph.MIN_HEIGHT_X;x<=Graph.MAX_HEIGHT_X;x+=(0.00125*(Graph.MAX_HEIGHT_X-Graph.MIN_HEIGHT_X))) {
                        if(!eq.equals(getText())) return;
                        
                        try{
                            mSymbols.define(mX, x);
                            double y = mSymbols.eval(equation[0]);
                            
                            if(y>(Graph.MAX_HEIGHT_Y*2) || y<(Graph.MIN_HEIGHT_Y*2) || y==Double.NaN) {
                                series.add(x, MathHelper.NULL_VALUE);
                            }
                            else{
                                series.add(x, y);
                            }
                        } catch(SyntaxException e) {
                            e.printStackTrace();
                        }
                    }
                }
                else if(equation[1].equals(mX) && !equation[0].contains(mX)) {
                    for(double y=Graph.MIN_HEIGHT_Y;y<=Graph.MAX_HEIGHT_Y;y+=(0.00125*(Graph.MAX_HEIGHT_Y-Graph.MIN_HEIGHT_Y))) {
                        if(!eq.equals(getText())) return;
                        
                        try{
                            mSymbols.define(mY, y);
                            double x = mSymbols.eval(equation[0]);
                            
                            if(x>(Graph.MAX_HEIGHT_X*2) || x<(Graph.MIN_HEIGHT_X*2) || x==Double.NaN) {
                                series.add(MathHelper.NULL_VALUE, y);
                            }
                            else{
                                series.add(x, y);
                            }
                        } catch(SyntaxException e) {
                            e.printStackTrace();
                        }
                    }
                }
                else{
                    for(double x=Graph.MIN_HEIGHT_X;x<=Graph.MAX_HEIGHT_X;x+=(0.01*(Graph.MAX_HEIGHT_X-Graph.MIN_HEIGHT_X))) {
                        for(double y=Graph.MAX_HEIGHT_Y;y>=Graph.MIN_HEIGHT_Y;y-=(0.01*(Graph.MAX_HEIGHT_Y-Graph.MIN_HEIGHT_Y))) {
                            if(!eq.equals(getText())) return;
                            try{
                                mSymbols.define(mX, x);
                                mSymbols.define(mY, y);
                                Double leftSide = mSymbols.eval(equation[0]);
                                Double rightSide = mSymbols.eval(equation[1]);
                                if(leftSide < 0 && rightSide < 0) {
                                    if(leftSide*0.97 >= rightSide && leftSide*1.03 <= rightSide) {
                                        series.add(x, y);
                                        break;
                                    }
                                }
                                else{
                                    if(leftSide*0.97 <= rightSide && leftSide*1.03 >= rightSide) {
                                        series.add(x, y);
                                        break;
                                    }
                                }
                            } catch(SyntaxException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                
                try{
                    g.getDataset().removeSeries(g.getSeries());
                } catch(NullPointerException e) {
                    e.printStackTrace();
                }
                g.setSeries(series);
                g.getDataset().addSeries(series);
                
                if(graph!=null) graph.repaint();
            }
        }).start();
    }
    
    void findEigenvalue() {
        RealMatrix matrix = solveMatrix();
        if(matrix == null || matrix.getColumnDimension() != matrix.getRowDimension()) return;
        
        String result = "";
        try{
            for(double d : new EigenDecomposition(matrix, 0).getRealEigenvalues()) {
                for (int precision = mLineLength; precision > 6; precision--) {
                    result = tryFormattingWithPrecision(d, precision);
                    if (result.length() <= mLineLength) {
                        break;
                    }
                }
                
                result += ",";
            }
        } catch(NonSymmetricMatrixException e) {
            e.printStackTrace();
            setText(mErrorString);
            return;
        }

        mResult = result;
        mDisplay.setText(mResult, CalculatorDisplay.Scroll.UP);
        setDeleteMode(DELETE_MODE_CLEAR);
    }
    
    void findDeterminant() {
        RealMatrix matrix = solveMatrix();
        if(matrix == null || matrix.getColumnDimension() != matrix.getRowDimension()) return;
        
        String result = "";
        for (int precision = mLineLength; precision > 6; precision--) {
            result = tryFormattingWithPrecision(new LUDecomposition(matrix).getDeterminant(), precision);
            if (result.length() <= mLineLength) {
                break;
            }
        }

        mResult = result;
        mDisplay.setText(mResult, CalculatorDisplay.Scroll.UP);
        setDeleteMode(DELETE_MODE_CLEAR);
    }
    
    RealMatrix solveMatrix() {
        final LinearLayout matrices = (LinearLayout) mContext.findViewById(R.id.matrices);
        RealMatrix matrix = null;
        boolean plus = false;
        boolean multiplication = false;
        boolean dot = false;
        boolean dotCalculated = false;
        boolean cross = false;
        for(int i=0; i<matrices.getChildCount(); i++) {
            View v = matrices.getChildAt(i);
            switch(v.getId()) {
            case(R.id.matrixPlus):
                if(matrix == null || plus || multiplication || dot || dotCalculated || cross  || (i==matrices.getChildCount()-2)) {
                    setText(mErrorString);
                    return null;
                }
                plus = true;
                break;
            case(R.id.matrixMul):
                if(matrix == null || plus || multiplication || dot || dotCalculated  || cross  || (i==matrices.getChildCount()-2)) {
                    setText(mErrorString);
                    return null;
                }
                multiplication = true;
                break;
            case(R.id.matrixDot):
                if(matrix == null || plus || multiplication || dot || dotCalculated  || cross || (i==matrices.getChildCount()-2)) {
                    setText(mErrorString);
                    return null;
                }
                dot = true;
                break;
            case(R.id.matrixCross):
                if(matrix == null || plus || multiplication || dot || dotCalculated  || cross  || (i==matrices.getChildCount()-2)) {
                    setText(mErrorString);
                    return null;
                }
                cross = true;
                break;
            case(R.id.theMatrix):
                if(dotCalculated) {
                    setText(mErrorString);
                    return null;
                }
            
                LinearLayout theMatrix = (LinearLayout) v;
                
                int n = theMatrix.getChildCount();
                int m = ((LinearLayout) theMatrix.getChildAt(0)).getChildCount();
                
                double[][] matrixData = new double[n][m];
                
                for (int j=0; j<theMatrix.getChildCount(); j++) {
                    LinearLayout layout = (LinearLayout) theMatrix.getChildAt(j);
                    for(int k=0; k<layout.getChildCount(); k++) {
                        EditText view = (EditText) layout.getChildAt(k);
                        matrixData[j][k] = Integer.valueOf(view.getText().toString());
                    }
                }
                
                if(matrix == null) {
                    matrix = new Array2DRowRealMatrix(matrixData);
                }
                else if(plus) {
                    try{
                        matrix = matrix.add(new Array2DRowRealMatrix(matrixData));
                    } catch(MatrixDimensionMismatchException e) {
                        e.printStackTrace();
                        setText(mErrorString);
                        return null;
                    }
                    plus = false;
                }
                else if(multiplication) {
                    try{
                        matrix = matrix.multiply(new Array2DRowRealMatrix(matrixData));
                    } catch(DimensionMismatchException e) {
                        e.printStackTrace();
                        setText(mErrorString);
                        return null;
                    }
                    multiplication = false;
                }
                else if(dot) {
                    if(matrix.getColumnDimension() > 1) {
                        setText(mErrorString);
                        return null;
                    }
                    
                    RealVector vector = matrix.getColumnVector(0);
                    RealVector vectorData = new Array2DRowRealMatrix(matrixData).getColumnVector(0);
                    String result = tryFormattingWithPrecision(vector.dotProduct(vectorData), 2);
                    mResult = result;
                    mDisplay.setText(mResult, CalculatorDisplay.Scroll.UP);
                    setDeleteMode(DELETE_MODE_CLEAR);
                    dot = false;
                    dotCalculated = true;
                }
                else if(cross) {
                    if(matrix.getColumnDimension() > 1 || matrix.getRowDimension() != 3) {
                        setText(mErrorString);
                        return null;
                    }
                    
                    RealVector vector = matrix.getColumnVector(0);
                    RealVector vectorData = new Array2DRowRealMatrix(matrixData).getColumnVector(0);
                    Vector3D result = Vector3D.crossProduct(CommonMathUtils.toVector3D(vector), CommonMathUtils.toVector3D(vectorData));
                    matrix = CommonMathUtils.toRealMatrix(result);
                    cross = false;
                }
                else{
                    setText(mErrorString);
                    return null;
                }
                break;
            case(R.id.matrixAdd):
                if(dotCalculated) {
                    return null;
                }
                break;
            }
        }
        if(matrix == null) return null;
        
        matrices.removeViews(0, matrices.getChildCount()-1);
        
        double[][] data = matrix.getData();
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        
        DisplayMetrics metrics = new DisplayMetrics();
        mContext.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        final float logicalDensity = metrics.density;
        
        final LinearLayout theMatrix = new LinearLayout(mContext);
        theMatrix.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        theMatrix.setOrientation(LinearLayout.VERTICAL);
        theMatrix.setId(R.id.theMatrix);
        theMatrix.setBackgroundDrawable(mContext.getResources().getDrawable(R.drawable.matrix_background));
        for (int i=0; i<data.length; i++) {
            LinearLayout layout = new LinearLayout(mContext);
            layout.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            layout.setOrientation(LinearLayout.HORIZONTAL);
            for(int j=0; j<data[i].length; j++) {
                EditText view = (EditText) inflater.inflate(R.layout.single_matrix_input_box, null);
                view.setWidth((int) (75*logicalDensity+0.5));
                view.setHeight((int) (100*logicalDensity+0.5));
                view.setText(Double.valueOf(data[i][j]).intValue()+"");
                view.setOnFocusChangeListener(new OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if(hasFocus) {
                            View theMatrix = (View) v.getParent().getParent();
                            ViewGroup parent = (ViewGroup) theMatrix.getParent();
                            parent.removeView(theMatrix);
                        }
                    }
                });
                
                layout.addView(view);
            }
            theMatrix.addView(layout);
        }
        theMatrix.setFocusable(true);
        theMatrix.setFocusableInTouchMode(true);
        theMatrix.requestFocus();

        matrices.addView(theMatrix, matrices.getChildCount()-1);
        
        return matrix;
    }

    public Mode getMode() {
        return mode;
    }

    public String setMode(Mode mode) {
        String text = updateTextToNewMode(getText(), this.mode, mode);
        this.mode = mode;
        return text;
    }

    private String updateTextToNewMode(final String originalText, final Mode mode1, final Mode mode2) {
        if(mode1.equals(mode2)) return originalText;
        String text = originalText;
        if(!text.equals(mErrorString) && !text.isEmpty() && !mode1.equals(mode2)) {
            String[] operations = text.split(REGEX_NUMBER);
            String[] numbers = text.split(REGEX_NOT_NUMBER);
            String[] translatedNumbers = new String[numbers.length];
            for(int i=0;i<numbers.length;i++) {
                if(!numbers[i].isEmpty())
                switch(mode1) {
                case BINARY:
                    switch(mode2) {
                    case BINARY:
                        break;
                    case DECIMAL:
                        try{
                            translatedNumbers[i] = newBase(numbers[i], 2, 10);
                        } catch(NumberFormatException e) {
                            return mErrorString;
                        } catch (SyntaxException e) {
                            return mErrorString;
                        }
                        break;
                    case HEXADECIMAL:
                        try{
                            translatedNumbers[i] = newBase(numbers[i], 2, 16);
                        } catch(NumberFormatException e) {
                            return mErrorString;
                        } catch (SyntaxException e) {
                            return mErrorString;
                        }
                        break;
                    }
                    break;
                case DECIMAL:
                    switch(mode2) {
                    case BINARY:
                        try{
                            translatedNumbers[i] = newBase(numbers[i], 10, 2);
                        } catch(NumberFormatException e) {
                            return mErrorString;
                        } catch (SyntaxException e) {
                            return mErrorString;
                        }
                        break;
                    case DECIMAL:
                        break;
                    case HEXADECIMAL:
                        try{
                            translatedNumbers[i] = newBase(numbers[i], 10, 16);
                        } catch(NumberFormatException e) {
                            return mErrorString;
                        } catch (SyntaxException e) {
                            return mErrorString;
                        }
                        break;
                    }
                    break;
                case HEXADECIMAL:
                    switch(mode2) {
                    case BINARY:
                        try{
                            translatedNumbers[i] = newBase(numbers[i], 16, 2);
                        } catch(NumberFormatException e) {
                            return mErrorString;
                        } catch (SyntaxException e) {
                            return mErrorString;
                        }
                        break;
                    case DECIMAL:
                        try{
                            translatedNumbers[i] = newBase(numbers[i], 16, 10);
                        } catch(NumberFormatException e) {
                            e.printStackTrace();
                            return mErrorString;
                        } catch (SyntaxException e) {
                            e.printStackTrace();
                            return mErrorString;
                        }
                        break;
                    case HEXADECIMAL:
                        break;
                    }
                    break;
                }
            }
            text = "";
            Object[] o = removeWhitespace(operations);
            Object[] n = removeWhitespace(translatedNumbers);
            if(originalText.substring(0,1).matches(REGEX_NUMBER)) {
                for(int i=0;i<o.length && i<n.length;i++) {
                    text += n[i];
                    text += o[i];
                }
            }
            else{
                for(int i=0;i<o.length && i<n.length;i++) {
                    text += o[i];
                    text += n[i];
                }
            }
            if(o.length > n.length) {
                text += o[o.length-1];
            }
            else if(n.length > o.length) {
                text += n[n.length-1];
            }
        }
        return text;
    }
    
    private Object[] removeWhitespace(String[] strings) {
        ArrayList<String> formatted = new ArrayList<String>(strings.length);
        for(String s : strings) {
            if(s!=null && !s.isEmpty()) formatted.add(s);
        }
        return formatted.toArray();
    }

    private String toDecimal(String number, int base) {
        String[] split = number.split("\\.");

        String wholeNumber = "";
        String decimalNumber = "";
        wholeNumber = Long.toString(Long.parseLong(split[0], base));
        if(split.length==1) return wholeNumber;
        decimalNumber = Long.toString(Long.parseLong(split[1], base)) + "/" + base + "^" + split[1].length();
        return "(" + wholeNumber + "+(" + decimalNumber + "))";
    }

    private final static int PRECISION = 8;
    private String newBase(String originalNumber, int originalBase, int base) throws SyntaxException{
        if(originalBase != 10) {
            originalNumber = Double.toString(mSymbols.eval(toDecimal(originalNumber, originalBase)));
        }
        String[] split = originalNumber.split("\\.");

        String wholeNumber = "";
        String decimalNumber = "";
        switch(base) {
        case 2:
            wholeNumber = Long.toBinaryString(Long.parseLong(split[0]));
            break;
        case 10:
            wholeNumber = split[0];
            break;
        case 16:
            wholeNumber = Long.toHexString(Long.parseLong(split[0]));
            break;
        }
        if(split.length==1 || Long.valueOf(split[1])==0) return wholeNumber.toUpperCase();

        double decimal = Double.parseDouble("0." + split[1]);
        for(int i=0,id=0;decimal!=0 && i<=PRECISION;i++) {
            decimal *= base;
            id = (int) Math.floor(decimal);
            decimal -= id;
            decimalNumber += Integer.toHexString(id);
        }
        return (wholeNumber + "." + decimalNumber).toUpperCase();
    }

//    private String addComas(String text) {
//        NumberFormat formatter = new DecimalFormat("##,###");
//        String[] pieces = text.split(".");
//        
//        String result = formatter.format(pieces[0]);
//        for(int i=1;i<pieces.length;i++) {
//            result += "." + pieces[i];
//        }
//        return result;
//    }
}