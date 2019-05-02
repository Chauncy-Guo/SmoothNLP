package com.smoothnlp.nlp.basic;
import java.util.*;
import java.util.stream.Collectors;

public class SEntity {

    public int charStart;

    public int charEnd;

    public String text ;

    public String nerTag;

    /**
     * mapping from token index to SToken; Index START FROM 1
     */
    public Map<Integer, SToken> sTokenList;

    public String normalizedEntityTag;

    public SEntity(){}

    public SEntity(int charStart, int charEnd, SToken token, String nerTag){
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.text = token.getToken();
        this.nerTag = nerTag;
    }

    public SEntity(int charStart, int charEnd, String token, String nerTag){
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.text = token;
        this.nerTag = nerTag;
    }

    public String toString(){
        if (nerTag == null){
            return null;
        }else{
            // TO DO
            return this.text+"/"+this.nerTag;
        }
    }

    public String getText(){
        return this.sTokenList.entrySet()
        .stream()
        .sorted()
        .map(e->e.getValue().token)
        .collect(Collectors.joining(""));
    }

}
