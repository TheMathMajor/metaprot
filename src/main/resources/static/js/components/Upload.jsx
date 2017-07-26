import React, {Component} from 'react';
import TopNavBar from './TopNavBar';
import SideNavBar from './SideNavBar';
import FileUploadForm from './FileUploadForm';
import FileTree from './FileTree';
import Footer from './Footer';
import { Link } from 'react-router-dom';
import { connect } from 'react-redux';
import { Form, FormGroup, FormControl, ControlLabel, Button, HelpBlock } from 'react-bootstrap'
import { resetTree, addFileToTree, setToken } from '../actions';
import { validateToken, getTreeData } from '../util/upload';

class Upload extends Component {

    constructor(props) {
        super(props);
        this.state = {
            tokenInput: "",
            tokenWarning: null
        }
        this.handleTokenSubmit = this.handleTokenSubmit.bind(this);
        this.handleTokenInput = this.handleTokenInput.bind(this);

    }

   /* componentWillMount() {
        // in case of page refresh, re-load token and sessionData if cached in sessionStorage
        var token = sessionStorage.getItem("sessionToken");
        if (token) {
            this.props.setToken(token);

            var sessionData = sessionStorage.getItem("root");
            if (sessionData) {
                this.props.resetTree();
                JSON.parse(sessionData).forEach(filename => {
                    this.props.addFileToTree(filename)
                })
            }
        }
    }*/

    // user retrieving file(s) via token
    handleTokenSubmit(e) {
        e.preventDefault();
        var token = this.state.tokenInput;
        var self = this;

        validateToken(token).then( response => {
            if(response == "true") {

                self.props.resetTree();
                self.props.setToken(token);

                getTreeData(token).then( data => {
                    data.forEach(filename => {
                        self.props.addFileToTree(filename);
                    })
                }).catch(e=>console.log(e))
            }
            else {
                console.log("TOKEN INVALID");
                //$('#token-num-display').css("opacity", 1);
                alert("Token invalid, please try again");
            }
        });

    }

    handleTokenInput(e) {
        var tokenInput = e.target.value;
        this.setState({tokenInput});
    }



    render() {
        return (
            <div>
                <h2>Upload a File</h2>
                <FileUploadForm />



                <h2>Retrieve Files</h2>
                <div className="well well-lg">
                    <Form inline onSubmit={this.handleTokenSubmit} id="retrieve-file">
                            <ControlLabel htmlFor="inputToken">Token</ControlLabel>
                            <FormControl onChange={this.handleTokenInput} id="inputToken" placeholder="token number"/>

                        <FormControl type="submit" value="Go"/>

                    </Form>
                </div>
            </div>
        )

    }

}



export default connect(null, { resetTree, addFileToTree, setToken })(Upload);