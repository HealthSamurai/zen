(ns zen.alias-test
  (:require
   [zen.core :as zen]
   [matcho.core :as matcho]
   [clojure.test :refer [deftest is testing]])) 

(deftest alias-test
  #"TODO: add alias remove test"
  (def test-namespaces
    '{ns1 {:ns   ns1
           sym1 {:foo :bar}

           tag1 {:zen/tags #{zen/tag}}

           tag-alias1 ns1/tag1
           tag-alias2 ns1/tag-alias1

           sch1
           {:zen/tags #{zen/schema zen/tag}
            :type zen/map
            :keys {:a {:type zen/string}
                   :b {:type zen/symbol :tags #{tag1}}}}}

      ns2 {:ns    ns2
           sym21 {:foo1 :bar2}
           sym22 {:foo2 :bar2}

           tag21 {:zen/tags #{zen/tag}}
           tag22 {:zen/tags #{zen/tag}}

           tagged-sym1 {:zen/tags #{tag21}}
           tagged-sym2 {:zen/tags #{tag22}}

           sch2
           {:zen/tags #{zen/schema}
            :type zen/map
            :keys {:a {:type zen/string}}}}

      myns {:ns     myns
            :import #{ns1}
            :alias  ns2

            sym1  ns1/sym1
            sch1  ns1/sch1
            sym22 {:baz :quux}

            tag1 ns1/tag1
            tagged-sym1 {:zen/tags #{tag1}}
            tagged-sym2 {:zen/tags #{ns1/tag1}}
            tagged-sym3 {:zen/tags #{ns1/tag-alias1}}
            tagged-sym4 {:zen/tags #{ns1/tag-alias2}}

            tag22 {:zen/tags #{zen/tag}}
            tagged-sym21  {:zen/tags #{tag21}}
            tagged-sym221 {:zen/tags #{tag22}}
            tagged-sym222 {:zen/tags #{ns2/tag22}}

            sch2-res1
            {:zen/tags #{zen/schema sch1}
             :b tagged-sym1}

            sch2-res2
            {:zen/tags #{zen/schema sch1}
             :b tagged-sym2}

            sch2-res3
            {:zen/tags #{zen/schema sch1}
             :b tagged-sym3}

            sch2-res4
            {:zen/tags #{zen/schema sch1}
             :b tagged-sym4}}})

  (def ztx (zen/new-context {:unsafe true :memory-store test-namespaces}))

  (zen/load-ns ztx (get test-namespaces 'myns))

  (is (empty? (zen/errors ztx)))

  (matcho/match
    (zen/get-symbol ztx 'myns/tagged-sym1)
    {:zen/tags #{'ns1/tag1}})

  (matcho/match
    (zen/get-symbol ztx 'myns/tagged-sym2)
    {:zen/tags #{'ns1/tag1}})

  (matcho/match
    (zen/get-symbol ztx 'myns/tagged-sym3)
    {:zen/tags #{'ns1/tag1}})

  (matcho/match
    (zen/get-symbol ztx 'myns/tagged-sym4)
    {:zen/tags #{'ns1/tag1}})

  (testing "symbol alias"
    (matcho/match
     (zen/get-symbol ztx 'myns/sym1)
      '{:zen/name ns1/sym1}))

  (testing "ns alias"
    (matcho/match
     (zen/get-symbol ztx 'myns/sym21)
      '{:zen/name ns2/sym21})

    (testing "monkey patch"
      (matcho/match
       (zen/get-symbol ztx 'myns/sym22)
        '{:zen/name myns/sym22})))

  (testing "tags alias"
    (testing "symbol alias"
      (is (= #{'myns/tagged-sym1 'myns/tagged-sym2 'myns/tagged-sym3 'myns/tagged-sym4}
             (zen/get-tag ztx 'myns/tag1)))

      (is (= #{'myns/tagged-sym1 'myns/tagged-sym2 'myns/tagged-sym3 'myns/tagged-sym4}
             (zen/get-tag ztx 'ns1/tag1))))

    (testing "ns alias"
      (is (= #{'myns/tagged-sym21 'ns2/tagged-sym1}
             (zen/get-tag ztx 'myns/tag21)))

      (is (= #{'myns/tagged-sym21 'ns2/tagged-sym1}
             (zen/get-tag ztx 'ns2/tag21)))

      (testing "monkey patch"
        (is (= #{'myns/tagged-sym221}
               (zen/get-tag ztx 'myns/tag22)))

        (is (= #{'myns/tagged-sym222 'ns2/tagged-sym2}
               (zen/get-tag ztx 'ns2/tag22))))))

  (testing "validate with alias"
    (is (zen/get-symbol ztx 'myns/sch1))

    (matcho/match
     (zen/validate ztx #{'ns1/sch1} {:a 1})
      '{:errors
        [{:message "Expected type of 'string, got 'long",
          :type "string.type",
          :path [:a],
          :schema [ns1/sch1 :a]}]})

    (matcho/match
     (zen/validate ztx #{'myns/sch1} {:a 1})
      '{:errors
        [{:message "Expected type of 'string, got 'long"
          :type "string.type"
          :path [:a]
          :schema [myns/sch1 :a :type]}]})

    (matcho/match
     (zen/validate ztx #{'ns2/sch2} {:a 1})
      '{:errors
        [{:message "Expected type of 'string, got 'long",
          :type "string.type",
          :path [:a],
          :schema [ns2/sch2 :a]}]})

    (matcho/match
     (zen/validate ztx #{'myns/sch2} {:a 1})
      '{:errors
        [{:message "Expected type of 'string, got 'long"
          :type "string.type"
          :path [:a]
          :schema [myns/sch2 :a :type]}]})))


(deftest backward-alias-test
  (def test-namespaces
    '{lib {:ns lib
           foo :zen/declare
           bar {:cfg foo}}

      user {:ns     user
            :import #{lib}
            lib/foo myfoo
            myfoo   {:baz :quux}}})

  (def ztx (zen/new-context {:unsafe true :memory-store test-namespaces}))

  (zen/load-ns ztx (get test-namespaces 'user))

  (matcho/match (zen/errors ztx)
                empty?)

  (matcho/match (zen/get-symbol ztx 'lib/foo)
                {:baz :quux})

  (matcho/match (zen/get-symbol ztx 'user/myfoo)
                {:baz :quux})

  (is (= (zen/get-symbol ztx 'lib/foo)
         (zen/get-symbol ztx 'user/myfoo))))
