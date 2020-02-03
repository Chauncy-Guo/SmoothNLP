package com.smoothnlp.nlp.model.crfagu;

import java.io.*;
import java.text.DecimalFormat;
import java.util.*;

/** 本段根据 hanlp 的crf++ 活的，增加支持embedding特征，'E'表示
 * crf 需要根据模板生成的 state function ，根据crf++ http://taku910.github.io/crfpp/ 对于template type 生成逻辑解释
 * unigram %x[#,#] 生成一个CRFs中的state function
 *  func1 = if(output = B-NP and feature="u01:DT") return 1 else return 0
 *  number of feature functions generated by a template amounts to (L * N) ,where L is the number of output classes , and N is the number of unique string expanded  from the given template.
 *  即为 function :one-hot encode feature -> label
 *
 * 想增肌embedding 逻辑其实考虑增加的是增加了一个固定为%x[0,0]位置, embedding 维度固定为 eDim
 * 对于每个 i dim 上面 为 func1 = f(output = B-NP and feature = embedding-i-th) return 1 else return 0
 * 则单个维度上只有 L 个function ，整体增加了 eDim function
 *
 * dic_ 存储字符对应编码，和对应该位置出现该字符的统计数据；
 * dic_= <string, pair<integer1, integer2>> string 字符， pair(integer1) 编码， pair(integer) 统计值
 * Created by zhifac on 2017/3/18.
 */
public class EncoderFeatureIndex extends FeatureIndex {
    private HashMap<String, Pair<Integer, Integer>> dic_;
    private HashMap<String, Pair<Integer, Integer>> emb_dic_;

    public EncoderFeatureIndex(int n) {
        threadNum_ = n;
        dic_ = new HashMap<String, Pair<Integer, Integer>>(); // <字符，<编码1~y,字符出现次数>
        emb_dic_ = new HashMap<>();
    }

    /**
     * 根据 key 进行编码？
     * U 对于每个位置的每个模板生成 y_.size()个特征函数
     * B 对于每个位置的每个模板生成 y_.size() * y_.size()个特征函数
     * @param key
     * @return 返回生成的编码 ID，
     * 如果是key,是第一次出现，则增加至dic_中，并返回n, maxid_ += 对应L(unigram) or L*L (bigram)
     * 如果不是一次出现，则返回key 对应的编码值，存在于dic_中的pair(key), 并将dic_中的pair(value)+1 并入统计
     * 对于此时的统计并不考虑真实的key对应的Y，而是仅用于统计x次数；
     */
    public int getID(String key) {
        if (!dic_.containsKey(key)) {
            dic_.put(key, new Pair<Integer, Integer>(maxid_, 1));
            int n = maxid_;
            maxid_ += (key.charAt(0) == 'U' ? y_.size() : y_.size() * y_.size());
            return n;
        } else {
            Pair<Integer, Integer> pair = dic_.get(key);
            int k = pair.getKey();
            int oldVal = pair.getValue();
            dic_.put(key, new Pair<Integer, Integer>(k, oldVal + 1));
            return k;
        }
    }

    /*
    public int getEmbeddingID(String key){
        if(!emb_dic_.containsKey(key)){
            emb_dic_.put(key,new Pair<Integer, Integer>(maxEmbeddingId_,1));
            int n = maxEmbeddingId_;
            maxEmbeddingId_ += getEmbeddingVectorSize() * y_.size();
            return n ;
        }else{
            Pair<Integer,Integer> pair = emb_dic_.get(key);
            int k = pair.getKey();
            int oldVal = pair.getValue();
            emb_dic_.put(key,new Pair<Integer, Integer>(k,oldVal+1));
            return k;
        }
    }
    */

    /**
     * 用于打开特征模板文件，检测特征模板合法性
     * 'U' 支持unigram 特征
     * 'B' 支持bigram 特征
     * 'E' 支持embedding 特征
     * @param filename
     * @return
     */
    private boolean openTemplate(String filename, String embeddingFile) {
        InputStreamReader isr = null;
        try {
            isr = new InputStreamReader(new FileInputStream(filename), "UTF-8");
            BufferedReader br = new BufferedReader(isr);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == ' ' || line.charAt(0) == '#') {
                    continue;
                } else if (line.charAt(0) == 'U') {
                    unigramTempls_.add(line.trim());
                } else if (line.charAt(0) == 'B') {
                    bigramTempls_.add(line.trim());
                } else if (line.charAt(0) == 'E'){
                    embeddingTempls_.add(line.trim());
                    if(embeddingFile !=null && embedding == null){
                        isSupportEmbedding = true;
                        embedding = new EmbeddingImpl(embeddingFile); //初始化embeddingVector
                    }
                } else{
                    System.err.println("unknown type: " + line);
                }
            }
            br.close();
            templs_ = makeTempls(unigramTempls_, bigramTempls_); //embeddingTempls_暂时未做添加;
        } catch(Exception e) {
            if (isr != null) {
                try {
                    isr.close();
                } catch(Exception e2) {
                }
            }
            e.printStackTrace();
            System.err.println("Error reading " + filename);
            return false;
        }
        return true;
    }

    /**
     * 暂时只打开训练文件，将label存入y_ (list<string>)中(实际存储为label set)，训练文件的每行的默认分割符为[\t ];并检测所有训练数据是否与第一行数据格式一致
     * @param filename
     * @return
     */
    private boolean openTagSet(String filename) {
        int max_size = 0;
        InputStreamReader isr = null;
        y_.clear();
        try {
            isr = new InputStreamReader(new FileInputStream(filename), "UTF-8");
            BufferedReader br = new BufferedReader(isr);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() == 0) {
                    continue;
                }
                char firstChar = line.charAt(0);
                if (firstChar == '\0' || firstChar == ' ' || firstChar == '\t') {
                    continue;
                }
                String[] cols = line.split("[\t ]", -1);
                if (max_size == 0) {
                    max_size = cols.length;
                }
                if (max_size != cols.length) {
                    String msg = "inconsistent column size: " + max_size +
                        " " + cols.length + " " + filename + "\n error line:" + line;
                    throw new RuntimeException(msg);
                }
                xsize_ = cols.length - 1;
                if (y_.indexOf(cols[max_size - 1]) == -1) {
                    y_.add(cols[max_size - 1]);
                }
            }
            Collections.sort(y_);
            br.close();
        } catch(Exception e) {
            if (isr != null) {
                try {
                    isr.close();
                } catch(Exception e2) {
                }
            }
            e.printStackTrace();
            System.err.println("Error reading " + filename);
            return false;
        }
        return true;
    }

    // filename1 为模板文件，用openTemplate()打开
    // filename2 暂时为训练文件，用 openTagSet() 打开，用来找到目前的tag(label)集合
    public boolean open(String filename1, String filename2) {
        checkMaxXsize_ = true;
        return openTemplate(filename1, null) && openTagSet(filename2);
    }

    public boolean open(String filename1, String filename2,String filename3){
        checkMaxXsize_ = true;
        return openTemplate(filename1, filename3) && openTagSet(filename2);
    }

    public boolean isSupportEmbedding() {
        return isSupportEmbedding;
    }

    public boolean save(String filename, boolean textModelFile) {
        try {
            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filename));
            oos.writeObject(Encoder.MODEL_VERSION);
            oos.writeObject(costFactor_);
            oos.writeObject(maxid_);
            if (max_xsize_ > 0) {
                xsize_ = Math.min(xsize_, max_xsize_);
            }
            oos.writeObject(xsize_);
            oos.writeObject(y_);
            oos.writeObject(unigramTempls_);
            oos.writeObject(bigramTempls_);
            List<Pair<String, Integer>> pairList = new ArrayList<Pair<String, Integer>>();
            for (String key: dic_.keySet()) {
                pairList.add(new Pair<String, Integer>(key, dic_.get(key).getKey()));
            }

            Collections.sort(pairList, new Comparator<Pair<String,Integer>>() {
                public int compare(Pair<String,Integer> one,
                                   Pair<String,Integer> another) {
                    return one.getKey().compareTo(another.getKey());
                }
            });
            List<String> keys = new ArrayList<String>();
            int[] values = new int[pairList.size()];
            int i = 0;
            for (Pair<String, Integer> pair: pairList) {
                keys.add(pair.getKey());
                values[i++] = pair.getValue();
            }
            DoubleArrayTrie dat = new DoubleArrayTrie();
            System.out.println("Building Trie");
            dat.build(keys, null, values, keys.size());
            System.out.println("Trie built.");

            oos.writeObject(dat.getBase());
            oos.writeObject(dat.getCheck());
            oos.writeObject(alpha_);

            // add support embedding params
            oos.writeObject(isSupportEmbedding);
            oos.writeObject(maxEmbeddingId_);
            oos.writeObject(embeddingTempls_);
            oos.writeObject(alphaEmbedding_);
            oos.writeObject(embedding.getVsize());

            HashMap<String, float[]> vector = embedding.getEmbeddingVector();
            oos.writeObject(vector);

            oos.close();

            if (textModelFile) {
                OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(filename + ".txt"), "UTF-8");
                osw.write("version: " + Encoder.MODEL_VERSION + "\n");
                osw.write("cost-factor: " + costFactor_ + "\n");
                osw.write("maxid: " + maxid_ + "\n");
                osw.write("xsize: " + xsize_ + "\n");
                osw.write("\n");
                for (String y: y_) {
                    osw.write(y + "\n");
                }
                osw.write("\n");
                for (String utempl: unigramTempls_) {
                    osw.write(utempl + "\n");
                }
                for (String bitempl: bigramTempls_) {
                    osw.write(bitempl + "\n");
                }
                osw.write("\n");
                for (Pair<String, Integer> pair: pairList) {
                    osw.write(pair.getValue() + " " + pair.getKey() + "\n");
                }
                osw.write("\n");

                for (int k = 0; k < maxid_; k++) {
                    String val = new DecimalFormat("0.0000000000000000").format(alpha_[k]);
                    osw.write(val + "\n");
                }

                //add support embedding params
                osw.write("\n");
                osw.write("isSuppportEmbedding: " + isSupportEmbedding+"\n");
                osw.write("maxembeddingid: " + maxEmbeddingId_ + "\n");
                osw.write("\n");

                for (String emtempl :embeddingTempls_){
                    osw.write(emtempl + "\n");
                }
                osw.write("\n");

                for(int k = 0 ; k< maxEmbeddingId_; k++){
                    String val = new DecimalFormat("0.0000000000000000").format(alphaEmbedding_[k]);
                    osw.write(val + "\n");
                }
                osw.write("\n");

                osw.write("embedding_size: " +embedding.getVsize()+"\n");

                for(String key: embedding.getEmbeddingKeySet()){
                    float[] vectorSet = embedding.getStrEmbedding(key);
                    StringBuffer sb = new StringBuffer();
                    sb.append(key);
                    for(int li = 0 ; li <embedding.getVsize(); li++){
                            sb.append("\t" + vectorSet[li]);
                    }
                    sb.append("\n");
                    osw.write(sb.toString());
                }

                osw.close();
            }
        } catch(Exception e) {
            e.printStackTrace();
            System.err.println("Error saving model to " + filename);
            return false;
        }
        return true;
    }

    public void clear() {

    }

    /**
     * 根据freq阈值，限制特征出现次数高于该值的特征被考虑进入feature，并由此于dic_ 中的id 进行 重新编码，更新maxid
     * @param freq 特征出现次数阈值
     * @param taggers
     */
    public void shrink(int freq, List<TaggerImpl> taggers) {
        if (freq <= 1) {
            return;
        }
        int newMaxId = 0;
        HashMap<Integer, Integer> old2new = new HashMap<Integer, Integer>();
        HashMap<String, Pair<Integer, Integer>> newDic_ = new HashMap<String, Pair<Integer, Integer>>();
        List<String> ordKeys = new ArrayList<String>(dic_.keySet());
        // update dictionary in key order, to make result compatible with crfpp
        Collections.sort(ordKeys);
        for (String key: ordKeys) {
            Pair<Integer, Integer> featFreq = dic_.get(key);
            if (featFreq.getValue() >= freq) {
                old2new.put(featFreq.getKey(), newMaxId);
                newDic_.put(key, new Pair<Integer,Integer>(newMaxId, featFreq.getValue()));
                newMaxId += (key.charAt(0) == 'U' ? y_.size() : y_.size() * y_.size());
            }
        }

        for (TaggerImpl tagger: taggers) {
            List<List<Integer>> featureCache = tagger.getFeatureCache_();
            for (int k = 0; k < featureCache.size(); k++) {
                List<Integer> featureCacheItem = featureCache.get(k);
                List<Integer> newCache = new ArrayList<Integer>();
                for (Integer it : featureCacheItem) {
                    if (old2new.containsKey(it)) {
                        newCache.add(old2new.get(it));
                    }
                }
                newCache.add(-1);
                featureCache.set(k, newCache);
            }
        }
        maxid_ = newMaxId;
        dic_ = newDic_;
    }

    public boolean convert(String textmodel, String binarymodel) {
        try {
            InputStreamReader isr = new InputStreamReader(new FileInputStream(textmodel), "UTF-8");
            BufferedReader br = new BufferedReader(isr);
            String line;

            int version = Integer.valueOf(br.readLine().substring("version: ".length()));
            costFactor_ = Double.valueOf(br.readLine().substring("cost-factor: ".length()));
            maxid_ = Integer.valueOf(br.readLine().substring("maxid: ".length()));
            xsize_ = Integer.valueOf(br.readLine().substring("xsize: ".length()));
            System.out.println("Done reading meta-info");
            br.readLine();

            while ((line = br.readLine()) != null && line.length() > 0) {
                y_.add(line);
            }
            System.out.println("Done reading labels");
            while ((line = br.readLine()) != null && line.length() > 0) {
                if (line.startsWith("U")) {
                    unigramTempls_.add(line);
                } else if (line.startsWith("B")) {
                    bigramTempls_.add(line);
                }
            }
            System.out.println("Done reading templates");
            dic_ = new HashMap<String, Pair<Integer, Integer>>();
            while ((line = br.readLine()) != null && line.length() > 0) {
                String[] content = line.trim().split(" ");
                if (content.length != 2) {
                    System.err.println("feature indices format error");
                    return false;
                }
                dic_.put(content[1], new Pair<Integer, Integer>(Integer.valueOf(content[0]), 1));
            }
            System.out.println("Done reading feature indices");
            List<Double> alpha = new ArrayList<Double>();
            while ((line = br.readLine()) != null && line.length() > 0) {
                alpha.add(Double.valueOf(line));
            }
            System.out.println("Done reading weights");
            alpha_ = new double[alpha.size()];
            for (int i = 0; i < alpha.size(); i++) {
                alpha_[i] = alpha.get(i);
            }

            // add support embedding params
            isSupportEmbedding = Boolean.valueOf(br.readLine().substring("isSuppportEmbedding: ".length()));
            maxEmbeddingId_ = Integer.valueOf(br.readLine().substring("maxembeddingid: ".length()));

            System.out.println("Done reading embedding constant");

            while((line = br.readLine())!= null && line.length() > 0){
                if(line.startsWith("E")){
                    embeddingTempls_.add(line);
                }
            }
            System.out.println("Done reading embedding templates");
            List<Double> alphaEmbedding = new ArrayList<>();
            while((line = br.readLine()) != null && line.length() >0 ){
                alphaEmbedding.add(Double.valueOf(line));
            }
            System.out.println("Done reading embedding params weights");
            alphaEmbedding_ = new double[alphaEmbedding.size()];
            for(int i =0 ;i < alphaEmbedding.size(); i++){
                alphaEmbedding_[i] = alphaEmbedding.get(i);
            }

            HashMap<String ,float[]> embeddingMap = new HashMap<>();
            int vsize = 0 ;
            while((line = br.readLine()) != null && line.length()>0){
                String [] splits = line.trim().split("\t");
                List<Float> vectorList = new ArrayList<>();
                for(int i = 1; i<splits.length; i++){
                    vectorList.add(Float.parseFloat(splits[i]));
                }
                vsize = vectorList.size();
                float[] vectors = new float[vsize];
                for(int i= 0 ;i <vectorList.size(); i++){
                    vectors[i] = vectorList.get(i);
                }
                embeddingMap.put(splits[0],vectors);
            }
            embedding = new EmbeddingImpl();
            embedding.setEmbeddingVector(embeddingMap);
            embedding.setVsize(vsize);
            System.out.println("Done reading embedding vectors");

            br.close();
            System.out.println("Writing binary model to " + binarymodel);
            return save(binarymodel, false);
        } catch(Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void main(String[] args) {

//        EncoderFeatureIndex featureIndex = new EncoderFeatureIndex(6);
//        featureIndex.convert("ner_crfpp_3gram.bin.txt","ner_crf.bin");

        if (args.length < 2) {
            return;
        } else {
            EncoderFeatureIndex featureIndex = new EncoderFeatureIndex(1);
            if (!featureIndex.convert(args[0], args[1])) {
                System.err.println("Fail to convert text model");
            }
        }
    }
}
